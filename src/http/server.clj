(ns http.server
  (:gen-class)
  (:require [aleph.http :as http]
            [manifold.deferred :as d]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :as cli])
  (:import [java.io File]
           [java.net URLDecoder]
           [java.util Date Locale TimeZone]
           [java.text SimpleDateFormat]
           [net.sf.jmimemagic Magic MagicMatchNotFoundException]
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
  (try
    (.getMimeType (Magic/getMagicMatch file true))
    (catch MagicMatchNotFoundException _ nil)))

(defn inject-mime-type [handler]
  (fn [req]
    (d/chain
     (handler req)
     (fn [{:keys [status body headers] :as response}]
       (if (or (not= 200 status)
               (not (instance? File body))
               (contains? headers "content-type"))
         response
         (if-let [content-type (detect-mime-type body)]
           (assoc-in response [:headers "content-type"] content-type)
           response))))))

;;
;; cache control
;;

(def http-date-format "EEE, dd MMM yyyy HH:mm:ss zzz")

(def http-date-timezone (TimeZone/getTimeZone "GMT"))

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

(defn wrap-if-modified [no-cache? handler]
  (fn [{:keys [headers] :as req}]
    (d/chain
     (handler req)
     (fn [{:keys [status body] :as response}]
       (if (or (true? no-cache?)
               (not= 200 status)
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

(defn file-handler [no-listing? {:keys [request-method uri headers] :as req}]
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

            (and (.isDirectory file)
                 (true? no-listing?))
            not-found

            (and (.isDirectory file)
                 (str/ends-with? uri "/"))
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

;; this is actually duplicates some effort
;; the Aleph would do anyways... the problem
;; is that content-length header will be set
;; (if missing) after `aleph.http.core/send-message`
;; is invoked, and we have literally no access
;; to what's happening there. in fact, we need
;; content-length to be calculated to log req &
;; response properly
(defn inject-content-length [handler]
  (fn [req]
    (d/chain
     (handler req)
     (fn [{:keys [body] :as response}]
       (let [len (when (some? body)
                   (cond
                     (string? body)
                     (count body)

                     (instance? File body)
                     (.length ^File body)

                     :else
                     nil))]
         (if (nil? len)
           response
           (assoc-in response [:headers "content-length"] len)))))))

(defn method-keyword->str [request-method]
  (-> request-method name str/upper-case))

(defn wrap-logging [handler]
  (fn [{:keys [request-method uri] :as req}]
    (d/chain
     (handler req)
     (fn [{:keys [status headers] :as response}]
       (let [method' (method-keyword->str request-method)
             ;; we rely on `inject-content-length` being
             ;; invoked earlier :expressionless:
             len (get headers "content-length" "-")]
         (log/warnf "\"%s %s HTTP/1.1\" %s %s" method'  uri status len))
       response))))

(defn stop []
  (when-let [s @server]
    (.close s)
    (log/warn "HTTP server was stopped")
    (reset! server nil)))

(def cli-options
  [["-p" "--port <PORT>" "Port number"
    :default 8000
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-b" "--bind <IP>" "Address to bind to"
    :default default-bind]
   [nil "--no-index" "Disable directory listings" :default false]
   [nil "--no-cache" "Disable cache headers" :default false]
   [nil "--no-compression" "Disable deflate and gzip compression" :default false]
   ["-h" "--help"]])

;; todo(kachayev): CORS, basic auth, list of files to exclude
;; todo(kachayev): update pipeline with HttpObjectAggregator
;; todo(kachayev): range requests (we need latest Aleph changes to be merged)
;; todo(kachayev): Etag support
(defn -main [& args]
  (let [{:keys [options
                arguments
                summary
                errors]} (cli/parse-opts args cli-options)]
    (cond
      (some? errors)
      (do
        (doseq [error errors]
          (println error))
        (println "Use `--help` flag to get more information.")
        (System/exit 0))

      (true? (:help options))
      (do
        (println summary)
        (System/exit 0))

      :else
      (let [port (if (not (empty? arguments))
                   (Integer/parseInt (first arguments))
                   (:port options))
            bind-address (:bind options)
            compress? (not (:no-compression options))
            handler (->> (partial file-handler (:no-index options))
                         parse-accept
                         (wrap-if-modified (:no-cache options))
                         inject-mime-type
                         inject-server-name
                         inject-content-length
                         wrap-logging)
            s (http/start-server handler {:port port
                                          :compression? compress?
                                          ;; file operations are blocking,
                                          ;; nevertheless the directory listing
                                          ;; is fast enought to be done on I/O
                                          ;; threads and reading/sending files
                                          ;; is performed either using zero-copy
                                          ;; or streaming with a relatively small
                                          ;; chunks size. meaning... we don't need
                                          ;; a separate executor here.
                                          :executor :none})]
        (log/warnf "Serving HTTP on %s port %s" bind-address port)
        (reset! server s)
        s))))
