(defproject diehard "0.8.4-SNAPSHOT"
  :description "Safety utilities for Clojure"
  :url "http://github.com/sunng87/diehard"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [net.jodah/failsafe "2.0.1"]
                 [org.clojure/spec.alpha "0.2.176"]]
  :plugins [[lein-codox "0.10.3"]
            [lein-eftest "0.5.2"]]
  :codox {:output-path "docs/"
          :source-uri "https://github.com/sunng87/diehard/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}}
  :deploy-repositories {"releases" :clojars})
