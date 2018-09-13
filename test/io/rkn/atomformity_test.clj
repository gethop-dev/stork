(ns io.rkn.atomformity-test
  (:require [clojure.test :refer :all]
            [io.rkn.atomformity :refer :all]
            [datomic.api :refer [q db] :as d]
            [migrations.txes :refer [txes-foo txes-bar]]))

(def uri  "datomic:mem://test")
(defn fresh-conn []
  (d/delete-database uri)
  (d/create-database uri)
  (d/connect uri))

(defn attr
  ([ident]
   (attr ident :db.type/string))
  ([ident value-type]
   {:db/id (d/tempid :db.part/db)
    :db/ident ident
    :db/valueType value-type
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}))

(def sample-norm {:id :test1/norm1
                  :tx-data [(attr :test/attribute1)
                            (attr :test/attribute2)]})

(def sample-norm2 {:id :test2/norm1
                   :tx-data [(attr :test/attribute1)
                             (attr :test/attribute2 :db.type/nosuch)]})

(def sample-norms-map-txes-fns {:test-txes-fn/norm1
                                {:txes-fn 'migrations.txes/txes-foo}})

(deftest test-ensure-conforms
  (testing "installs all expected norms"

    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norm)
      (is (has-attribute? (db conn) :test/attribute1))
      (is (has-attribute? (db conn) :test/attribute2))
      (is (= :already-conformed
             (ensure-conforms conn sample-norm))))))
