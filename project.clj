(defproject nasus "0.1.2"
  :description "A simple zero-configuration command-line HTTP files server that scales"
  :url "https://github.com/kachayev/nasus"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.cli "0.4.1"]
                 [aleph "0.4.7-alpha5"]
                 [org.apache.tika/tika-core "1.20"]]
  :main http.server
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :deploy-repositories [["clojars" {:sign-releases false}]])
