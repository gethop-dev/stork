(ns magnetcoop.stork
  (:require [datomic.api :refer [q db] :as d]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(def installed-migrations-attribute :stork/installed-migrations)
(def ensure-migration-tx
  "Ident for a function installed in datomic.
  See `ensure-migration-should-be-installed`"
  :stork/ensure-migration-should-be-installed)

(def ensure-migration-should-be-installed
  "Transaction function to ensure that each migration is executed exactly only
  when a migration-id isn't known to had been installed in past."
  (d/function
    '{:lang :clojure
      :params [db inst-migs-attr migration-id tx-data]
      :code (when-not (seq (q '[:find ?tx
                                :in $ ?installed-migrations-attribute ?migration-id
                                :where
                                [?tx ?installed-migrations-attribute ?migration-id]]
                              db inst-migs-attr migration-id))
              (cons {:db/id (d/tempid :db.part/tx)
                     inst-migs-attr migration-id}
                    tx-data))}))

(defn read-resource
  "Reads and returns data from a resource containing edn text.
  An optional argument allows specifying opts for clojure.edn/read"
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

(defn ensure-stork-schema
  "Makes sure that library vitals are installed into the db:
  1) An attribute to store information about successfully installed migrations.
  2) A custom datomic function for safe migration installing (see `:stork/ensure-migration-should-be-installed-txfn`)"
  [conn]
  (when-not (has-attribute? (db conn) installed-migrations-attribute)
    (d/transact conn [{:db/id (d/tempid :db.part/db)
                       :db/ident installed-migrations-attribute
                       :db/valueType :db.type/keyword
                       :db/cardinality :db.cardinality/one
                       :db/unique :db.unique/value
                       :db/index true
                       :db.install/_attribute :db.part/db}]))
  (when-not (has-function? (db conn) ensure-migration-tx)
    (d/transact conn [{:db/id (d/tempid :db.part/user)
                       :db/ident ensure-migration-tx
                       :db/fn ensure-migration-should-be-installed}])))

(defn installed?
  "Checks if a migration-id is known to be already installed into the db."
  [db migration-id]
  (and (has-attribute? db installed-migrations-attribute)
       (boolean (q '[:find ?tx .
                     :in $ ?installed-migrations ?migration-id
                     :where [?tx ?installed-migrations ?migration-id]]
                   db installed-migrations-attribute migration-id))))

(defn maybe-timeout-synch-schema [conn maybe-timeout]
  (if maybe-timeout
    (let [result (deref (d/sync-schema conn (d/basis-t (d/db conn))) maybe-timeout ::timed-out)]
      (if (= result ::timed-out)
        (throw (ex-info
                 "Timed out calling synch-schema between Stork transactions"
                 {:timeout maybe-timeout}))
        result))
    @(d/sync-schema conn (d/basis-t (d/db conn)))))

(defn eval-tx-data-fn
  "Tries to resolve the symbol that contains the function.
  It's evaluation should result in a valid tx-data."
  [conn tx-data-fn]
  (try (require (symbol (namespace tx-data-fn)))
       {:tx-data ((resolve tx-data-fn) conn)}
       (catch Throwable t
         {:ex (str "Exception evaluating " tx-data-fn ": " t)})))

(defn complement-migration
  "If migration contains tx-data, function returns migration unchanged.
  If it contains tx-data-fn instead then it evaluates resolved symbol
  and merges the result with migration."
  [conn migration]
  (let [tx-data-fn (:tx-data-fn migration)]
    (cond-> migration
            tx-data-fn (merge (eval-tx-data-fn conn tx-data-fn)))))

(defn handle-tx-data
  "Tries to transact tx-data using custom :stork/:stork/ensure-migration-should-be-installed."
  [conn migration-id tx-data sync-schema-timeout]
  (try
    (let [safe-tx [ensure-migration-tx
                   installed-migrations-attribute
                   migration-id
                   tx-data]
          _ (maybe-timeout-synch-schema conn sync-schema-timeout)
          tx-result @(d/transact conn [safe-tx])]
      (when (next (:tx-data tx-result))
        tx-result))
    (catch Throwable t
      (let [reason (.getMessage t)
            data {:reason reason}]
        (throw (ex-info reason data t))))))

(defn handle-migration
  "Checks if a migration's id is known to be installed already.
  If it is, return ::already-installed.
  Otherwise transact tx-data (be it explicitly given or evaluated from tx-data-fn)
  and return transaction's result."
  [conn {:keys [id] :as migration}]
  (if (installed? (db conn) id)
    ::already-installed
    (let [sync-schema-timeout (:stork.setting/sync-schema-timeout migration)
          ;; TODO handle resolution exceptions
          {:keys [tx-data ex]} (complement-migration conn migration)]
      (handle-tx-data conn id tx-data sync-schema-timeout))))

(defn tx-data-xor-tx-data-fn?
  "Returns true if:
  a) tx-data is present and tx-data-fn is not
  b) tx-data-fn is present and tx-data is not
  Returns false otherwise."
  [{:keys [tx-data tx-data-fn]}]
  (or (and tx-data (not tx-data-fn))
      (and tx-data-fn (not tx-data))))

(s/def ::migration (s/and (s/keys :req-un [::id]
                                  :opt-un [::tx-data ::tx-data-fn])
                          tx-data-xor-tx-data-fn?))

(defn ensure-installed
  "Ensure that migration is installed, be it schema, data or otherwise.
   A migration is represented in a format of a map with kv pairs:

     :id         - unique identifier of the migration. The id needs to be of type clojure.lang.Keyword.
                   No migrations having same id will get installed after this one has been installed.
     :tx-data    - a vector of datoms to be transacted if migration is not installed yet.
     :tx-data-fn - an alternative to `:tx-data`.
                   It's a symbol representing a function that will be ran to produce tx-data.
                   The function will be ran with one argument - conn.

  If migration hasn't been installed before then function will return with a transaction result.
  It will return `::already-installed` otherwise."
  [conn migration]
  {:pre [(s/valid? ::migration migration)]}
  (ensure-stork-schema conn)
  (handle-migration conn migration))
