(defproject dev.gethop/stork "0.1.6"
  :description "Idempotent and atomic datom transacting for Datomic. Heavily inspired on rkneufeld/conformity."
  :url "http://github.com/gethop-dev/stork"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.9.8"
  :plugins [[jonase/eastwood "1.2.3"]
            [lein-cljfmt "0.8.0"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.0"]
                                  [com.datomic/datomic-free "0.9.5697"]]
                   :source-paths ["dev"]}}
  :deploy-repositories [["snapshots" {:url "https://clojars.org/repo"
                                      :username :env/CLOJARS_USERNAME
                                      :password :env/CLOJARS_PASSWORD
                                      :sign-releases false}]
                        ["releases"  {:url "https://clojars.org/repo"
                                      :username :env/CLOJARS_USERNAME
                                      :password :env/CLOJARS_PASSWORD
                                      :sign-releases false}]])
