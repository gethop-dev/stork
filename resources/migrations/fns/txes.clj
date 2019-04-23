(ns migrations.fns.txes
  (:require [datomic.api :as d]))

(defn attr
  ([ident]
   (attr ident :db.type/string))
  ([ident value-type]
   {:db/id (d/tempid :db.part/db)
    :db/ident ident
    :db/valueType value-type
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}))

(defn new-attr [_]
  [(attr :test/attribute)])

(defn populate-meaning-of-life [_]
  [{:db/id (d/tempid :db.part/user)
    :life/meaning 42}])

(defn txfn-no-args []
  [(attr :test/txfn-no-args)])
