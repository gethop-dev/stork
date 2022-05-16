(defproject dev.gethop/stork "0.1.6"
  :description "Idempotent and atomic datom transacting for Datomic. Heavily inspired on rkneufeld/conformity."
  :url "http://github.com/gethop-dev/stork"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.8.1"
  :plugins [[jonase/eastwood "0.3.4"]
            [lein-cljfmt "0.6.2"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [com.datomic/datomic-free "0.9.5697"]]
                   :source-paths ["dev"]}}
  :deploy-repositories [["snapshots" {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_password
                                      :sign-releases false}]
                        ["releases"  {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_password
                                      :sign-releases false}]])
