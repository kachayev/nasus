(defproject nasus "0.1.7"
  :description "A simple zero-configuration command-line HTTP files server that scales"
  :url "https://github.com/kachayev/nasus"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.cli "0.4.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.26"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [aleph "0.4.7-alpha5"]
                 [org.apache.tika/tika-core "1.20"]]
  :main http.server
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :deploy-repositories [["clojars" {:sign-releases false}]])
