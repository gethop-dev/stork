(ns io.rkn.atomformity
  (:require [datomic.api :refer [q db] :as d]
            [clojure.java.io :as io]))

(def default-conformity-attribute :conformity/conformed-norms)
(def conformity-ensure-norm-tx :conformity/ensure-norm-tx)

(def ensure-norm-tx-txfn
  "Transaction function to ensure each norm tx is executed exactly once"
  #db/fn {:lang :clojure
          :params [db attr norm tx]
          :code (when-not (seq (q '[:find ?tx
                                    :in $ ?na ?nv
                                    :where
                                    [?tx ?na ?nv]]
                                  db attr norm))
                  (cons {:db/id (d/tempid :db.part/tx)
                         attr norm}
                        tx))})

(defn read-resource
  "Reads and returns data from a resource containing edn text. An
  optional argument allows specifying opts for clojure.edn/read"
  ([resource-name]
   (read-resource {:readers *data-readers*} resource-name))
  ([opts resource-name]
   (->> (io/resource resource-name)
        (io/reader)
        (java.io.PushbackReader.)
        (clojure.edn/read opts))))

(defn has-attribute?
  "Returns true if a database has an attribute named attr-name"
  [db attr-name]
  (-> (d/entity db attr-name)
      :db.install/_attribute
      boolean))

(defn has-function?
  "Returns true if a database has a function named fn-name"
  [db fn-name]
  (-> (d/entity db fn-name)
      :db/fn
      boolean))

(defn ensure-conformity-schema
  "Ensure that the two attributes and one transaction function
  required to track conformity via the conformity-attr keyword
  parameter are installed in the database."
  [conn]
  (when-not (has-attribute? (db conn) default-conformity-attribute)
    (d/transact conn [{:db/id (d/tempid :db.part/db)
                       :db/ident default-conformity-attribute
                       :db/valueType :db.type/keyword
                       :db/cardinality :db.cardinality/one
                       :db/doc "Name of this transaction's norm"
                       :db/index true
                       :db.install/_attribute :db.part/db}]))
  (when-not (has-function? (db conn) conformity-ensure-norm-tx)
    (d/transact conn [{:db/id (d/tempid :db.part/user)
                       :db/ident conformity-ensure-norm-tx
                       :db/doc "Ensures each norm tx is executed exactly once"
                       :db/fn ensure-norm-tx-txfn}])))

(defn conforms-to?
  "Does database have a norm installed?
     norm-id  - the keyword name of the norm you want to check"
  [db norm-id]
  (and (has-attribute? db default-conformity-attribute)
       (q '[:find ?tx .
            :in $ ?na ?nv
            :where [?tx ?na ?nv]]
          db default-conformity-attribute norm-id)))

(defn maybe-timeout-synch-schema [conn maybe-timeout]
  (if maybe-timeout
    (let [result (deref (d/sync-schema conn (d/basis-t (d/db conn))) maybe-timeout ::timed-out)]
      (if (= result ::timed-out)
        (throw (ex-info "Timed out calling synch-schema between conformity transactions" {:timeout maybe-timeout}))
        result))
    @(d/sync-schema conn (d/basis-t (d/db conn)))))

(defn eval-tx-data-fn
  "Given a connection and a symbol referencing a function on the classpath...
     - `require` the symbol's namespace
     - `resolve` the symbol
     - evaluate the function, passing it the connection
     - return the result"
  [conn tx-data-fn]
  (try (require (symbol (namespace tx-data-fn)))
       {:tx-data ((resolve tx-data-fn) conn)}
       (catch Throwable t
         {:ex (str "Exception evaluating " tx-data-fn ": " t)})))

(defn get-norm
  "Pull from `norm-map` the `norm-name` value. If the norm contains a
  `txes-fn` key, allow processing of that key to stand in for a `txes`
  value. Returns the value containing transactable data."
  [conn norm-map]
  (let [tx-data-fn (:tx-data-fn norm-map)]
    (cond-> norm-map
            tx-data-fn (merge (eval-tx-data-fn conn tx-data-fn)))))

(defn handle-tx-data [conn norm-id tx-data ex sync-schema-timeout]
  (try
    (let [safe-tx [conformity-ensure-norm-tx
                   default-conformity-attribute
                   norm-id
                   tx-data]
          _ (maybe-timeout-synch-schema conn sync-schema-timeout)
          tx-result @(d/transact conn [safe-tx])]
      (when (next (:tx-data tx-result))
        tx-result))
    (catch Throwable t
      (let [reason (.getMessage t)
            data {:reason reason}]
        (throw (ex-info reason data t))))))

(defn handle-norm
  "Reduces norms from a norm-map specified by a seq of norm-names into
  a transaction result accumulator"
  [conn {:keys [id] :as norm-map}]
  (let [sync-schema-timeout (:conformity.setting/sync-schema-timeout norm-map)
        {:keys [tx-data ex]} (get-norm conn norm-map)]
    (if (conforms-to? (db conn) id)
      :already-conformed
      (handle-tx-data conn id tx-data ex sync-schema-timeout))))

(defn ensure-conforms
  "Ensure that norms represented as datoms are conformed-to (installed), be they
  schema, data or otherwise.

  [norm-map]

   a data map contains:
     :norm-id    - unique identifier for norm to conform
     :tx-data    - the data to install
     :tx-data-fn - An alternative to tx-data, pointing to a symbol representing
                   a fn on the classpath that will return transaction data.

  On success, returns a vector of maps with values for :norm-name, :tx-index,
  and :tx-result for each transaction that improved the db's conformity.

  On failure, throws an ex-info with a reason and data about any partial
  success before the failure."
  [conn norm-map]
  (ensure-conformity-schema conn)
  (handle-norm conn norm-map))
