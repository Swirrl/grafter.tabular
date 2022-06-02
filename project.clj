(defproject grafter/grafter.tabular "0.11.5"
  :description "Tools for the hard graft of data processing"
  :url "http://grafter.org/"
  :min-lein-version "2.9.1"
  :license {:name "Eclipse Public License - v1.0"
            :url "https://www.eclipse.org/legal/epl-v10.html"}

  :deploy-repositories [["releases" :clojars]]

  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/tools.logging "0.3.1"]
                 [grafter "0.11.7"] ;; depends on grafter.rdf.protocols
                 [commons-io/commons-io "2.11.0"]
                 [org.apache.commons/commons-compress "1.21"]
                 [me.raynes/fs "1.4.6"]
                 [potemkin "0.4.3"]

                 [org.clojure/data.csv "0.1.3"]
                 [grafter/clj-excel "0.1.0" :exclusions [commons-codec]]
                 [incanter/incanter-core "1.5.7" :exclusions [net.sf.opencsv/opencsv commons-codec]]]


  :codox {:defaults {:doc "FIXME: write docs"
                     :doc/format :markdown}
          :output-dir "api-docs"

          ;; TODO change this when we merge back to master
          :src-dir-uri "http://github.com/Swirrl/grafter/blob/0.8.x-SNAPSHOT/"
          :src-linenum-anchor-prefix "L"}

  ;; Prevent Java process from appearing as a GUI app in OSX when
  ;; Swing classes are loaded.
  :jvm-opts ["-Dapple.awt.UIElement=true"]

  :pedantic? true

  :profiles {
             :dev {:plugins [[com.aphyr/prism "0.1.1"] ;; autotest support simply run: lein prism
                             [codox "0.8.10"]]

                   :dependencies [[com.aphyr/prism "0.1.3"]
                                  [org.slf4j/slf4j-simple "1.7.21"]
                                  [prismatic/schema "1.1.3"]
                                  [criterium "0.4.4"]]

                   :resource-paths ["dev/resources"]

                   :env {:dev true}}})
