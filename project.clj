(defproject nasus "0.1.7"
  :description "A simple zero-configuration command-line HTTP files server that scales"
  :url "https://github.com/kachayev/nasus"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies []
  :main http.server
  :plugins [[lein-tools-deps "0.4.5"]]
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :lein-tools-deps/config {:config-files [:install :user :project]}
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :deploy-repositories [["clojars" {:sign-releases false}]])
