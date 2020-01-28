(ns http.server-test
  (:require [aleph.http :as http]
            [aleph.netty :refer [port]]
            [byte-streams :refer [convert]]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [http.server :as nasus])
  (:import [java.io File]))

;; utilities

(defn pwd []
  (System/getProperty "user.dir"))

(defn fs-path [& pieces]
  (str/join File/separator pieces))

(defn base-url [server]
  (str "http://localhost:" (port server)))

(defn url
  [server path]
  (let [path (if (str/starts-with? path "/") (subs path 1) path)]
    (str (base-url server) "/" path)))

(defn body [response]
  (-> response :body (convert String)))

(defmacro with-nasus [var-name opts & body]
  `(let [~var-name (nasus/start (merge {:port 0} ~opts))]
     (try
       ~@body
       (finally
         (nasus/stop ~var-name)))))

;; tests

(deftest text-listing-test
  (with-nasus server {:dir (fs-path (pwd) "test_resources" "dir")}
    (let [response @(http/get (url server "/"))]
      (is (= (:status response) 200))
      (is (= (body response) "bar.html\r\nfoo.txt\r\n")))))

(deftest html-listing-test
  (with-nasus server {:dir (fs-path (pwd) "test_resources" "dir")}
    (let [response @(http/get (url server "/") {:headers {:accept "text/html"}})]
      (is (= (:status response) 200))
      (is (= (body response)
             "<!DOCTYPE html>\r
<title>Directory listing for: /</title>\r
<h2>Directory listing for: /</h3>\r
<hr/>\r
<ul>\r
<li><a href=\"bar.html\">bar.html</a></li>\r
<li><a href=\"foo.txt\">foo.txt</a></li>\r
</ul>\r
<hr/>\r
")))))

(deftest read-file-test
  (with-nasus server {:dir (fs-path (pwd) "test_resources" "dir")}
    (let [response @(http/get (url server "/foo.txt"))]
      (is (= (:status response) 200))
      (is (= (-> response :headers :content-type)) "text/plain")
      (is (= (body response) "foo\n")))))

(deftest mime-type-detection-test
  (with-nasus server {:dir (fs-path (pwd) "test_resources" "dir")}
    (let [response @(http/get (url server "/bar.html"))]
      (is (= (-> response :headers :content-type)) "text/html"))))

(deftest index-document-path-test
  (with-nasus server {:dir (fs-path (pwd) "test_resources" "dir")
                      :index-document-path "bar.html"}
    (let [response @(http/get (url server "/"))]
      (is (= (body response) "<!DOCTYPE html>\nhello")))))
