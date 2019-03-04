(ns http.server
  (:gen-class)
  (:require [aleph.http :as http]
            [manifold.deferred :as d]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.net URLDecoder]
           [java.util Date Locale TimeZone]
           [java.text SimpleDateFormat]
           [net.sf.jmimemagic Magic]
           [io.netty.handler.codec.http HttpResponseStatus]
           [io.netty.util.internal SystemPropertyUtil]))

(def default-bind "0.0.0.0")

(def default-port 8000)

(def default-http-cache-seconds 60)

(def user-dir (SystemPropertyUtil/get "user.dir"))

(defonce server (atom nil))

(def error-headers {"content-type" "text/plain; charset=UTF-8"
                    "connection" "close"})

(def forbidden {:status (.code HttpResponseStatus/FORBIDDEN)
                :headers error-headers
                ;; forces Aleph to close the connection
                ;; right after the response was flushed
                :aleph.http.client/close true})

(def method-not-allowed {:status (.code HttpResponseStatus/METHOD_NOT_ALLOWED)
                         :headers error-headers
                         ::close? true})

(def not-found {:status (.code HttpResponseStatus/NOT_FOUND)
                :headers error-headers
                :aleph.http.client/close true})

(def not-modified {:status (.code HttpResponseStatus/NOT_MODIFIED)})

;;
;; "Accept" header parser middleware
;;

(def accept-re #"([^,;\s]+)[^,]*?(?:;\s*q=(0(?:\.\d{0,3})?|1(?:\.0{0,3})?))?")

(defn parse-accept-header [accept-header]
  (->> accept-header
       (re-seq accept-re)
       (map (fn [[_ name q]]
		          {:name name :q (Float/parseFloat (or q "1.0"))}))))

(defn parse-accept [handler]
  (fn [{:keys [headers] :as req}]
    (let [accept-header (get headers "accept")]
      (handler (cond-> req
                 (not (str/blank? accept-header))
                 (assoc :accept (parse-accept-header accept-header)))))))

(defn accept? [content-type {:keys [accept]}]
  (some? (first (filter (fn [{:keys [name]}] (= content-type name)) accept))))

;;
;; mime types detector & injector middleware
;;

(defn detect-mime-type [^File file]
  (.getMimeType (Magic/getMagicMatch file true)))

(defn inject-mime-type [handler]
  (fn [req]
    (d/chain
     (handler req)
     (fn [{:keys [status body headers] :as response}]
       (if (or (not= 200 status)
               (not (instance? File body))
               (contains? headers "content-type"))
         response
         (let [content-type (detect-mime-type body)]
           (assoc-in response [:headers "content-type"] content-type)))))))

;;
;; cache control
;;

(def http-date-format "EEE, dd MMM yyyy HH:mm:ss zzz")

(def http-date-timezone (TimeZone/getTimeZone "GTM"))

(defn http-date-formatter []
  (doto (SimpleDateFormat. http-date-format Locale/ENGLISH)
    (.setTimeZone http-date-timezone)))

(defn parse-if-modified-header [value]
  (let [formatter (http-date-formatter)]
    (/ (.getTime (.parse formatter value)) 1000.0)))

(defn inject-cache-headers [{:keys [body] :as response}]
  (let [last-modified (.lastModified ^File body)
        cache-header (str "private, max-age=" default-http-cache-seconds)
        formatter (http-date-formatter)
        modified-header (.format formatter (Date. last-modified))]
    (-> response
        (assoc-in [:headers "Cache-Control"] cache-header)
        (assoc-in [:headers "Last-Modified"] modified-header))))

(defn reply-with-if-modified [{:keys [headers]} {:keys [body] :as response}]
  (let [header (get headers "If-Modified-Since")]
    (if (str/blank? header)
      (inject-cache-headers response)
      (let [if-modified (parse-if-modified-header header)
            file-modified (/ (.lastModified ^File body) 1000.0)]
        (if (< if-modified file-modified)
          not-modified
          (inject-cache-headers response))))))

(defn wrap-if-modified [handler]
  (fn [{:keys [headers] :as req}]
    (d/chain
     (handler req)
     (fn [{:keys [status body] :as response}]
       (if (or (not= 200 status)
               (not (instance? File body)))
         response
         (reply-with-if-modified req response))))))

;;
;; Static files handler
;;

(def allowed-file-name #"[^-\\._]?[^<>&\\\"]*")

(def insecure-uri #".*[<>&\"].*")

(defn allowed-file-name? [name]
  (some? (re-matches allowed-file-name name)))

(defn secure? [uri]
  (and (not (str/includes? uri (str File/separator ".")))
       (not (str/includes? uri (str "." File/separator)))
       (not (str/starts-with? uri "."))
       (not (str/ends-with? uri "."))
       (nil? (re-matches insecure-uri uri))))

(defn sanitize-uri [uri]
  (let [uri (URLDecoder/decode uri "UTF-8")]
    (when (and (not (str/blank? uri))
               (str/starts-with? uri "/"))
      (let [uri (str/replace uri #"\/" File/separator)]
        (when (secure? uri)
          (str user-dir uri))))))

(defn reply-with-redirect [new-location]
  {:status (.code HttpResponseStatus/FOUND)
   :headers {"location" new-location}})

(defn dir-listing [directory]
  (for [file (.listFiles directory)
        :when (.canRead file)]
    (.getName file)))

(defn render-text [listing]
  (str (str/join "\r\n" listing) "\r\n"))

(defn render-html [uri listing]
  (let [links (->> listing
                   (filter allowed-file-name?)
                   (map #(str "<li><a href=\""
                              %1
                              "\">"
                              %1
                              "</a></li>\r\n")))]
    (str "<!DOCTYPE html>\r\n"
         "<title>"
         "Directory listing for: " uri
         "</title>\r\n"
         "<h2>Directory listing for: " uri "</h3>\r\n"
         "<hr/>\r\n<ul>\r\n"
         ;; we cannot go upper from a root directory anyways
         (when-not (= "/" uri) "<li><a href=\"../\">..</a></li>\r\n")
         (apply str links)
         "</ul>\r\n"
         "<hr/>\r\n")))

;; todo(kachayev): preaggregate directories upfront
(defn reply-with-listing [{:keys [uri] :as req} file]
  (let [listing (sort (dir-listing file))
        [content-type body] (if (accept? "text/html" req)
                              ["text/html" (render-html uri listing)]
                              ["text/plain" (render-text listing)])]
    {:status 200
     :headers {"content-type" (str content-type "; charset=UTF-8")}
     :body body}))

;; note, that mime types and cache headers will be
;; injected by appropriate middlewares later on
(defn reply-with-file [file]
  {:status 200
   :body file})

(defn file-handler [{:keys [request-method uri headers] :as req}]
  (if (not= :get request-method)
    method-not-allowed
    (let [path (sanitize-uri uri)]
      (if (nil? path)
        forbidden
        (let [file (io/file path)]
          (cond
            (.isHidden file)
            not-found

            (not (.exists file))
            not-found

            (and (.isDirectory file) (str/ends-with? uri "/"))
            (reply-with-listing req file)

            (.isDirectory file)
            (reply-with-redirect (str uri "/"))

            (not (.isFile file))
            forbidden

            :else
            (reply-with-file file)))))))

;;
;; utilities
;;

(defn inject-server-name [handler]
  (fn [req]
    (d/chain
     (handler req)
     #(assoc-in % [:headers "Server"] "Nasus"))))

(defn wrap-logging [handler]
  (fn [{:keys [request-method uri] :as req}]
    (d/chain
     (handler req)
     (fn [{:keys [status] :as response}]
       (log/warnf "%s %s %s" request-method uri status)
       response))))

(defn stop []
  (when-let [s @server]
    (.close s)
    (log/warn "HTTP server was stopped")
    (reset! server nil)))

;; todo(kachayev): more params (bind, cors, no index, no cache, basic auth)
;; todo(kachayev): update pipeline with HttpObjectAggregator
;; todo(kachayev): range requests (we need latest Aleph changes to be merged)
(defn -main [& args]
  (let [port (if (empty? args)
               default-port
               (Integer/parseInt (first args)))
        handler (-> file-handler
                    parse-accept
                    wrap-if-modified
                    inject-mime-type
                    inject-server-name
                    wrap-logging)
        s (http/start-server handler {:port port
                                      :compression? true})]
    (log/warnf "Serving HTTP on %s port %s" default-bind port)
    (reset! server s)
    s))
