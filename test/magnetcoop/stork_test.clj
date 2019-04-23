(ns magnetcoop.stork-test
  (:require [clojure.test :refer :all]
            [magnetcoop.stork :refer :all]
            [datomic.api :refer [q db] :as d]
            [migrations.fns.txes :refer [new-attr]]))

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

(def sample-migration {:id :m001/new-attributes
                       :tx-data [(attr :test/attribute1)
                                 (attr :test/attribute2)]})

(def sample-migration-altered {:id :m001/new-attributes
                               :tx-data [(attr :test/attribute1)
                                         (attr :test/attribute2)
                                         (attr :test/attribute3)]})

(deftest test-ensure-installed
  (testing "installs expected migration"
    (let [conn (fresh-conn)]
      (ensure-installed conn sample-migration)
      (is (has-attribute? (db conn) :test/attribute1))
      (is (has-attribute? (db conn) :test/attribute2))))

  (testing "installing another/altered migration with same migration-id is being ignored"
    (let [conn (fresh-conn)]
      (ensure-installed conn sample-migration)
      (ensure-installed conn sample-migration-altered)
      (is (has-attribute? (db conn) :test/attribute1))
      (is (has-attribute? (db conn) :test/attribute2))
      (is (not (has-attribute? (db conn) :test/attribute3)))
      (is (= (ensure-installed conn sample-migration) :magnetcoop.stork/already-installed))))

  (testing "throws exception if migration lacks vital parameters"
    (let [conn (fresh-conn)]
      (is (thrown? java.lang.AssertionError
                   (ensure-installed conn {:tx-data [(attr :animal/species)]})))
      (is (thrown? java.lang.AssertionError
                   (ensure-installed conn {:tx-data-fn 'migrations.fns.txes/new-attr})))
      (is (thrown? java.lang.AssertionError
                   (ensure-installed conn {:id :m006/creatures-that-live-on-dry-land})))))

    (testing "throws exception if migration contains both tx-data and tx-data-fn"
      (let [conn (fresh-conn)]
        (is (thrown? java.lang.AssertionError
                     (ensure-installed conn {:id :m006/creatures-that-live-on-dry-land
                                             :tx-data [(attr :animal/species)]
                                             :tx-data-fn 'migrations.fns.txes/new-attr})))))))

  (testing "throws exception if migration cannot be transacted"
    (let [conn (fresh-conn)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ensure-installed conn {:id :m002/txfn-cannot-be-executed
                                           :tx-data-fn 'migrations.fns.txes/txfn-no-args}))))))

(deftest test-migration-installed-to?
  (testing "returns truthy if migration is already installed"
    (let [conn (fresh-conn)]
      (ensure-installed conn sample-migration)
      (is (some? (installed? (db conn) :m001/new-attributes)))))

  (testing "returns false if"
    (testing "migration has not been installed"
      (let [conn (fresh-conn)]
        (ensure-stork-schema conn)
        (is (false? (installed? (db conn) :m001/new-attributes)))))

    (testing "installed-migrations-attribute does not exist"
      (let [conn (fresh-conn)]
        (is (and
              (false? (has-attribute? (db conn) installed-migrations-attribute))
              (false? (installed? (db conn) :m001/new-attributes))))))))

(deftest test-ensure-stork-schema
  (testing "it adds the stork schema if it is absent"
    (let [conn (fresh-conn)
          _ (ensure-stork-schema conn)]
      (is (has-attribute? (db conn) installed-migrations-attribute))
      (is (has-function? (db conn) ensure-migration-tx))))

  (testing "it does nothing if the stork schema exists"
    (let [conn (fresh-conn)
          count-txes (fn [db]
                       (-> (q '[:find ?tx
                                :where [?tx :db/txInstant]]
                              db)
                           count))
          _ (ensure-stork-schema conn)
          before (count-txes (db conn))
          _ (ensure-stork-schema conn)
          after (count-txes (db conn))]
      (is (= before after)))))

(deftest test-loads-migration-from-resource
  (testing "loads a datomic schema from edn in a resource"
    (let [migration (read-resource "migrations/001-alter-schema.edn")
          conn (fresh-conn)]
      (is (ensure-installed conn migration))
      (is (installed? (db conn) :m001/alter-schema))
      @(d/transact conn
                   [{:db/id (d/tempid :db.part/user)
                     :life/meaning 42}])
      (let [meaning-of-life (d/q '[:find ?meaning .
                                   :where
                                   [_ :life/meaning ?meaning]]
                                 (db conn))]
        (is (= meaning-of-life 42)))))
  (testing "derive tx-data from from txes-fn reference in a resource"
    (let [alter-schema-migration (read-resource "migrations/001-alter-schema.edn")
          populate-data-migration (read-resource "migrations/002-populate.edn")
          conn (fresh-conn)]
      (ensure-installed conn alter-schema-migration)
      (ensure-installed conn populate-data-migration)
      (let [meaning-of-life (d/q '[:find ?meaning .
                                   :where
                                   [_ :life/meaning ?meaning]]
                                 (db conn))]
        (is (= 42 meaning-of-life))))))
