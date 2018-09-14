(ns magnetcoop.atomformity-test
  (:require [clojure.test :refer :all]
            [magnetcoop.atomformity :refer :all]
            [datomic.api :refer [q db] :as d]
            [migrations.txes :refer [test3]]))

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

(def sample-norm-altered {:id :test1/norm1
                          :tx-data [(attr :test/attribute1)
                                    (attr :test/attribute2)
                                    (attr :test/attribute3)]})

(deftest test-ensure-conforms
  (testing "installs expected norm"
    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norm)
      (is (has-attribute? (db conn) :test/attribute1))
      (is (has-attribute? (db conn) :test/attribute2))))

  (testing "conforming another/altered norm with same norm-id is being ignored"
    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norm)
      (ensure-conforms conn sample-norm-altered)
      (is (has-attribute? (db conn) :test/attribute1))
      (is (has-attribute? (db conn) :test/attribute2))
      (is (not (has-attribute? (db conn) :test/attribute3)))
      (is (= (ensure-conforms conn sample-norm) :magnetcoop.atomformity/already-conformed))))

  (testing "throws exception if norm lacks vital parameters"
    (let [conn (fresh-conn)]
      (is (thrown? java.lang.AssertionError
                   (ensure-conforms conn {:tx-data [(attr :test3/attribute1)]})))
      (is (thrown? java.lang.AssertionError
                   (ensure-conforms conn {:tx-data-fn 'migrations.txes/test3})))
      (is (thrown? java.lang.AssertionError
                   (ensure-conforms conn {:id :test3/norm1}))))

    (testing "throws exception if norm contains both tx-data and tx-data-fn"
      (let [conn (fresh-conn)]
        (is (thrown? java.lang.AssertionError
                     (ensure-conforms conn {:id :test3/norm1
                                            :tx-data [(attr :test3/attribute1)]
                                            :tx-data-fn 'migrations.txes/test3})))))))

(deftest test-conforms-to?
  (testing "returns truthy if a norm is already installed"
    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norm)
      (is (some? (conforms-to? (db conn) :test1/norm1)))))

  (testing "returns false if"
    (testing "a norm has not been installed"
      (let [conn (fresh-conn)]
        (ensure-conformity-schema conn)
        (is (false? (conforms-to? (db conn) :test1/norm1)))))

    (testing "conformity-attr does not exist"
      (let [conn (fresh-conn)]
        (is (false? (conforms-to? (db conn) :test1/norm1)))))))

(deftest test-ensure-conformity-schema
  (testing "it adds the conformity schema if it is absent"
    (let [conn (fresh-conn)
          _ (ensure-conformity-schema conn)]
      (is (has-attribute? (db conn) default-conformity-attribute))
      (is (has-function? (db conn) conformity-ensure-norm-tx))))

  (testing "it does nothing if the conformity schema exists"
    (let [conn (fresh-conn)
          count-txes (fn [db]
                       (-> (q '[:find ?tx
                                :where [?tx :db/txInstant]]
                              db)
                           count))
          _ (ensure-conformity-schema conn)
          before (count-txes (db conn))
          _ (ensure-conformity-schema conn)
          after (count-txes (db conn))]
      (is (= before after)))))

(deftest test-loads-norms-from-a-resource
  (testing "loads a datomic schema from edn in a resource"
    (let [norm (read-resource "001-alter-schema.edn")
          conn (fresh-conn)]
      (is (ensure-conforms conn norm))
      (is (conforms-to? (db conn) :m001/alter-schema))
      @(d/transact conn
                   [{:db/id (d/tempid :db.part/user)
                     :life/meaning 42}])
      (let [meaning-of-life (d/q '[:find ?meaning .
                                   :where
                                   [_ :life/meaning ?meaning]]
                                 (db conn))]
        (is (= meaning-of-life 42)))))
  (testing "derive tx-data from from txes-fn reference in a resource"
    (let [alter-schema-norm (read-resource "001-alter-schema.edn")
          populate-data-norm (read-resource "002-populate.edn")
          conn (fresh-conn)]
      (ensure-conforms conn alter-schema-norm)
      (ensure-conforms conn populate-data-norm)
      (let [meaning-of-life (d/q '[:find ?meaning .
                                   :where
                                   [_ :life/meaning ?meaning]]
                                 (db conn))]
        (is (= 42 meaning-of-life))))))
