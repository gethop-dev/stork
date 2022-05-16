[![Build Status](https://travis-ci.org/magnetcoop/stork.svg?branch=master)](https://travis-ci.org/magnetcoop/stork)

# Stork

A Clojure/Datomic migrations library heavily inspired by [rkneufeld/conformity](https://github.com/rkneufeld/conformity).

It consumes migrations defined by explicit id and a transaction data (explicit or evaluated from function) and transacts the data once and only once.

## Dependency

Stork is available on clojars, and can be included in your leiningen `project.clj` by adding the following to `:dependencies`:

[![Clojars Project](https://clojars.org/magnetcoop/stork/latest-version.svg)](https://clojars.org/magnetcoop/stork)


## Usage

### Writing a migration:

If your migration is going to have an explicit transaction data then all you need is a map that contains unique migration id and the transaction data:
```clojure
;; resources/migrations/001-user-entity.edn
{:id :m001/user-entity
 :tx-data [{:db/id #db/id [:db.part/db]
            :db/ident :user/name
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one
            :db.install/_attribute :db.part/db}
           {:db/id #db/id [:db.part/db]
            :db/ident :user/email
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one
            :db.install/_attribute :db.part/db}]}
```

If transaction data necessary for your transaction needs to be calculated first, then you want to use `:tx-data-fn` instead of `:tx-data`. `:tx-data-fn` must be the fully qualified name of the function to invoke. And you need to make sure that the namespace for that function can be found in the application classpath. This function accepts one argument - a Datomic connection:
```clojure
;; resources/migrations/001-add-prefix-to-phone-numbers
{:id :m002/add-prefix-to-phone-numbers
 :tx-data-fn migrations.fns.m002/add-prefix-to-phone-numbers}
```

```clojure
;; resources/migrations/fns/m002.clj
(ns migrations.fns.m002
  (:require [datomic.api :as d]))

...

(defn add-prefix-to-phone-numbers [conn]
  (mapv
    (fn [[u-eid phone]]
      [:db/add u-eid :user/phone (str "+48" phone)])
    (get-all-users-eid-phone-pair conn)))
```

### Running a migration:

```clojure
(require '[dev.gethop.stork :as stork])

(->>
  (stork/read-resource "migrations/001-alter-schema.edn")
  (stork/ensure-installed conn))
```

If there is any problem trying to apply a migration, `stork/ensure-installed` throws ExceptionInfo (and adds relevant details to the exception data map).

### To know whether a norm has been installed (e.g. for logging purposes):

```clojure
(->>
  (stork/read-resource "migrations/001-alter-schema.edn")
  (:id)
  (stork/installed? db))
```
## License

Copyright (c) 2022 HOP Technologies

Distributed under the Eclipse Public License, the same as Clojure.
