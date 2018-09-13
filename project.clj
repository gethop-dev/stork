(defproject magnetcoop/atomformity "0.1.0"
  :description "Idempotent and atomic datom transacting for Datomic. Heavily inspired on rkneufeld/conformity."
  :url "http://github.com/magnetcoop/atomformity"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [com.datomic/datomic-free "0.9.5703"]
                                  [org.clojure/tools.namespace "0.2.3"]]
                   :source-paths ["dev"]}})
