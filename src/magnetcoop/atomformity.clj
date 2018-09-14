(ns magnetcoop.atomformity
  (:require [datomic.api :refer [q db] :as d]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(def default-conformity-attribute :conformity/conformed-norms)
(def conformity-ensure-norm-tx :conformity/ensure-norm-tx)

(def ensure-norm-tx-txfn
  "Transaction function to ensure that each norm is executed exactly only
  when a norm-id isn't known to had been transacted in past."
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
  "Checks if an attribute is installed into db schema."
  [db attr-name]
  (-> (d/entity db attr-name)
      :db.install/_attribute
      boolean))

(defn has-function?
  "Checks if an entity (queried by ident) has a function installed."
  [db fn-name]
  (-> (d/entity db fn-name)
      :db/fn
      boolean))

(defn ensure-conformity-schema
  "Makes sure that library vitals are installed into the db:
  1) An attribute to store information about successfully installed norms.
  2) A custom datomic function for safe norms installing (see `ensure-norm-tx-txfn`)"
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
  "Checks if a norm-id is known to be already installed into the db."
  [db norm-id]
  (and (has-attribute? db default-conformity-attribute)
       (boolean (q '[:find ?tx .
                     :in $ ?na ?nv
                     :where [?tx ?na ?nv]]
                   db default-conformity-attribute norm-id))))

(defn maybe-timeout-synch-schema [conn maybe-timeout]
  (if maybe-timeout
    (let [result (deref (d/sync-schema conn (d/basis-t (d/db conn))) maybe-timeout ::timed-out)]
      (if (= result ::timed-out)
        (throw (ex-info
                 "Timed out calling synch-schema between conformity transactions"
                 {:timeout maybe-timeout}))
        result))
    @(d/sync-schema conn (d/basis-t (d/db conn)))))

(defn eval-tx-data-fn
  "Tries to resolve the symbol that contains the function that should
  result in a valid tx-data."
  [conn tx-data-fn]
  (try (require (symbol (namespace tx-data-fn)))
       {:tx-data ((resolve tx-data-fn) conn)}
       (catch Throwable t
         {:ex (str "Exception evaluating " tx-data-fn ": " t)})))

(defn get-norm
  "If norm contains tx-data, function returns norm unchanged.
  If it contains tx-data-fn instead then it evaluates corresponding symbol
  and merges the result with norm."
  [conn norm-map]
  (let [tx-data-fn (:tx-data-fn norm-map)]
    (cond-> norm-map
            tx-data-fn (merge (eval-tx-data-fn conn tx-data-fn)))))

(defn handle-tx-data
  "Tries to transact tx-data using custom :conformity/ensure-norm-tx."
  [conn norm-id tx-data sync-schema-timeout]
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
  "Checks if a norm's id is known to be installed already.
  If it is, return ::already-conformed.
  Otherwise transact tx-data (be it explicitly given or evaluated from tx-data-fn)
  and return transaction's result."
  [conn {:keys [id] :as norm-map}]
  (if (conforms-to? (db conn) id)
    ::already-conformed
    (let [sync-schema-timeout (:conformity.setting/sync-schema-timeout norm-map)
          {:keys [tx-data]} (get-norm conn norm-map)]
      (handle-tx-data conn id tx-data sync-schema-timeout))))

(defn tx-data-xor-tx-data-fn?
  "Returns true if:
  a) tx-data is present and tx-data-fn is not
  b) tx-data-fn is present and tx-data is not
  Returns false otherwise."
  [{:keys [tx-data tx-data-fn]}]
  (or (and tx-data (not tx-data-fn))
      (and tx-data-fn (not tx-data))))

(s/def ::norm (s/and (s/keys :req-un [::id]
                             :opt-un [::tx-data ::tx-data-fn])
                     tx-data-xor-tx-data-fn?))

(defn ensure-conforms
  "Ensure that norm is conformed-to (installed), be they
   schema, data or otherwise.
   A norm is represented in a format of a map with kv pairs:

     :id         - unique identifier of the norm. The id needs to be of type clojure.lang.Keyword.
                   No norms having same id will get conformed after this one has been installed.
     :tx-data    - a vector of datoms to be transacted if norm is not installed yet.
     :tx-data-fn - an alternative to `:tx-data`.
                   It's a symbol representing a function that will be ran to produce tx-data.
                   The function will be ran with one argument - conn.

  If norm hasn't been installed before then function will return with a transaction result.
  It will return `::already-conformed` otherwise."
  [conn norm]
  {:pre [(s/valid? ::norm norm)]}
  (ensure-conformity-schema conn)
  (handle-norm conn norm))
