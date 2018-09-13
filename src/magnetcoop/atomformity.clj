(ns magnetcoop.atomformity
  (:require [datomic.api :refer [q db] :as d]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(def default-conformity-attribute :conformity/conformed-norms)
(def conformity-ensure-norm-tx :conformity/ensure-norm-tx)

(def ensure-norm-tx-txfn
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
  ([resource-name]
   (read-resource {:readers *data-readers*} resource-name))
  ([opts resource-name]
   (->> (io/resource resource-name)
        (io/reader)
        (java.io.PushbackReader.)
        (clojure.edn/read opts))))

(defn has-attribute?
  [db attr-name]
  (-> (d/entity db attr-name)
      :db.install/_attribute
      boolean))

(defn has-function?
  [db fn-name]
  (-> (d/entity db fn-name)
      :db/fn
      boolean))

(defn ensure-conformity-schema
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
        (throw (ex-info "Timed out calling synch-schema between conformity transactions" {:timeout maybe-timeout}))
        result))
    @(d/sync-schema conn (d/basis-t (d/db conn)))))

(defn eval-tx-data-fn
  [conn tx-data-fn]
  (try (require (symbol (namespace tx-data-fn)))
       {:tx-data ((resolve tx-data-fn) conn)}
       (catch Throwable t
         {:ex (str "Exception evaluating " tx-data-fn ": " t)})))

(defn get-norm
  [conn norm-map]
  (let [tx-data-fn (:tx-data-fn norm-map)]
    (cond-> norm-map
            tx-data-fn (merge (eval-tx-data-fn conn tx-data-fn)))))

(defn handle-tx-data [conn norm-id tx-data sync-schema-timeout]
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
  [conn {:keys [id] :as norm-map}]
  (if (conforms-to? (db conn) id)
    :already-conformed
    (let [sync-schema-timeout (:conformity.setting/sync-schema-timeout norm-map)
          {:keys [tx-data]} (get-norm conn norm-map)]
      (handle-tx-data conn id tx-data sync-schema-timeout))))

(defn tx-data-xor-tx-data-fn? [{:keys [tx-data tx-data-fn]}]
  (or (and tx-data (not tx-data-fn))
      (and tx-data-fn (not tx-data))))

(s/def ::norm (s/and (s/keys :req-un [::id]
                             :opt-un [::tx-data ::tx-data-fn])
                     tx-data-xor-tx-data-fn?))

(defn ensure-conforms
  [conn norm]
  {:pre [(s/valid? ::norm norm)]}
  (ensure-conformity-schema conn)
  (handle-norm conn norm))
