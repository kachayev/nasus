(defproject nasus "0.1.1"
  :description "A simple zero-configuration command-line HTTP files server that scales"
  :url "https://github.com/kachayev/simplehttpserver"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.cli "0.4.1"]
                 [aleph "0.4.7-alpha5"]
                 [net.sf.jmimemagic/jmimemagic "0.1.5"]]
  :main http.server
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :deploy-repositories [["clojars" {:sign-releases false}]])
