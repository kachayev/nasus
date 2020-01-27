(ns http.server
  (:gen-class)
  (:require [aleph.http :as http]
            [aleph.http.client-middleware :refer [basic-auth-value]]
            [manifold.deferred :as d]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :as cli])
  (:import [java.io File IOException]
           [java.nio.file Files Path FileSystems]
           [java.net URLDecoder]
           [java.util Date Locale TimeZone]
           [java.text SimpleDateFormat]
           [org.apache.tika Tika]
           [io.netty.handler.codec.http HttpResponseStatus]
           [io.netty.util.internal SystemPropertyUtil]))

(def default-bind "0.0.0.0")

(def default-port 8000)

(def default-http-cache-seconds 60)

(def error-headers {"content-type" "text/plain; charset=UTF-8"
                    "connection" "close"})

(def forbidden {:status (.code HttpResponseStatus/FORBIDDEN)
                :headers error-headers
                ;; forces Aleph to close the connection
                ;; right after the response was flushed
                :aleph.http.client/close true})

(def unauthorized {:status (.code HttpResponseStatus/UNAUTHORIZED)
                   :headers error-headers
                   :aleph.http.client/close true})

(def method-not-allowed {:status (.code HttpResponseStatus/METHOD_NOT_ALLOWED)
                         :headers error-headers
                         :aleph.http.client/close true})

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

(def ^Tika mime-detector (Tika.))

(defn detect-mime-type [^File file]
  (try
    (.detect mime-detector file)
    (catch IOException _ nil)))

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
    (quot (.getTime (.parse formatter value)) 1000)))

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
            file-modified (quot (.lastModified ^File body) 1000)]
        (if (< if-modified file-modified)
          (inject-cache-headers response)
          not-modified)))))

(defn wrap-if-modified [no-cache? handler]
  (if (true? no-cache?)
    handler
    (fn [{:keys [headers] :as req}]
      (d/chain
       (handler req)
       (fn [{:keys [status body] :as response}]
         (if (or (not= 200 status)
                 (not (instance? File body)))
           response
           (reply-with-if-modified req response)))))))

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
       (not (str/starts-with? uri ".."))
       (not (str/ends-with? uri "."))
       (nil? (re-matches insecure-uri uri))))

(defn sanitize-uri [user-dir uri]
  (let [uri (URLDecoder/decode uri "UTF-8")]
    (when (and (not (str/blank? uri))
               (str/starts-with? uri "/"))
      (let [uri (str/replace uri #"\/" File/separator)]
        (when (secure? (subs uri 1))
          (str user-dir uri))))))

(defn symlink? [^File file]
  (Files/isSymbolicLink ^Path (.toPath file)))

(defn reply-with-redirect [new-location]
  {:status (.code HttpResponseStatus/FOUND)
   :headers {"location" new-location}})

(defn matches-glob? [file]
  (let [cwd (System/getProperty "user.dir")
        fs (FileSystems/getDefault)]
    (fn [glob]
      (.matches
       (.getPathMatcher
        fs
        (str "glob:" cwd "/" glob))
       (.toPath file)))))

(defn dir-listing [directory excluded-globs follow-symlink? include-hidden?]
  (for [file (.listFiles directory)
        :when (and (.canRead file)
                   (or follow-symlink?
                       (not (symlink? file)))
                   (or include-hidden?
                       (not (.isHidden file)))
                   (not (some (matches-glob? file) excluded-globs)))]
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
(defn reply-with-listing
  [{:keys [uri] :as req} file excluded-globs follow-symlink? include-hidden?]
  (let [listing (sort (dir-listing file excluded-globs follow-symlink? include-hidden?))
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

(defn file-handler [{:keys [no-index exclude follow-symlink include-hidden dir]}
                    {:keys [request-method uri headers] :as req}]
  (if (not= :get request-method)
    method-not-allowed
    (let [path (sanitize-uri dir uri)
          excluded-globs (when exclude (str/split exclude #" "))]
      (if (nil? path)
        forbidden
        (let [file (io/file path)]
          (cond
            (and (.isHidden file)
                 (not include-hidden))
            not-found

            (not (.exists file))
            not-found

            (and (not follow-symlink) (symlink? file))
            not-found

            (and (.isDirectory file)
                 (true? no-index))
            not-found

            (and (.isDirectory file)
                 (str/ends-with? uri "/"))
            (reply-with-listing req file excluded-globs follow-symlink include-hidden)

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

;; todo(kachayev): should we check method & headers when serving OPTIONS?
(defn wrap-cors [settings handler]
  (if (nil? settings)
    handler
    (let [allowed-headers (:headers settings)
          cors-headers
          (cond-> {"Access-Control-Allow-Origin" (get settings :origin "*")
                   "Access-Control-Allow-Methods" (get settings :methods "GET, POST")
                   "Access-Control-Allow-Credentials" "true"}
            (some? allowed-headers)
            (assoc "Access-Control-Allow-Headers" allowed-headers))]
      (fn [{:keys [request-method headers] :as req}]
        (if (and (identical? :options request-method)
                 (some? (get headers "Access-Control-Request-Method")))
          {:status 200 :headers cors-headers}
          (d/chain'
           (handler req)
           #(update % :headers merge cors-headers)))))))

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
         (log/infof "\"%s %s HTTP/1.1\" %s %s" method'  uri status len))
       response))))

(defn password-prompt! []
  (if-let [console (System/console)]
    (let [pw (String. (.readPassword console  "Enter password: " nil))]
      (if (empty? pw)
        (do (println "Password cannot be empty!")
            (recur))
        pw))
    (throw (Exception. "No console available"))))

(defn maybe-inject-auth [auth handler]
  (if (nil? auth)
    handler
    (fn [{:keys [headers] :as req}]
      (if (= (get headers "Authorization") auth)
        (handler req)
        (-> req
            (update :headers dissoc "authorization")
            (assoc-in [:headers "WWW-Authenticate"] "Basic realm=\"Nasus\"")
            (assoc :status 401))))))

(defn parse-auth
  "Make sure password is present, if not prompt for it."
  [auth]
  (let [[user pw] (str/split auth #":" 2)]
    (basic-auth-value
     (if (some? pw)
       auth
       (str user ":" (password-prompt!))))))

(defn start [options]
  (let [handler (->> (partial file-handler (select-keys options [:no-index
                                                                 :follow-symlink
                                                                 :include-hidden
                                                                 :exclude
                                                                 :dir]))
                     (wrap-cors (when (true? (:cors options))
                                  {:origin (:cors-origin options)
                                   :methods (:cors-methods options)
                                   :headers (:cors-allow-headers options)}))
                     parse-accept
                     (wrap-if-modified (:no-cache options))
                     inject-mime-type
                     inject-server-name
                     inject-content-length
                     (maybe-inject-auth (:basic-auth options))
                     wrap-logging)]
    (http/start-server handler {:port (:port options)
                                :compression? (:compress? options)
                                ;; file operations are blocking,
                                ;; nevertheless the directory listing
                                ;; is fast enough to be done on I/O
                                ;; threads and reading/sending files
                                ;; is performed either using zero-copy
                                ;; or streaming with a relatively small
                                ;; chunks size. meaning... we don't need
                                ;; a separate executor here.
                                :executor :none})))

(defn stop [server]
  (when server
    (.close server)
    (log/info "HTTP server was stopped")))

(def cli-options
  [["-p" "--port <PORT>" "Port number"
    :default default-port
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-b" "--bind <IP>" "Address to bind to"
    :default default-bind]
   [nil "--dir <DIR>" "Directory to serve the files"
    :default (SystemPropertyUtil/get "user.dir")
    :parse-fn #(.getCanonicalFile (File. %))
    :validate [#(.isDirectory %) "Must be an existing directory"]]
   [nil "--auth <USER[:PASSWORD]>" "Basic auth"]
   [nil "--exclude <GLOB>" (str "Exclude certain file-paths specified by either "
                                "a single glob, or whitespace delimited series of globs:\n"
                                "\"target/*\"  -> direct descendants of target\n"
                                "\"target/**\" -> all descendants of target\n"
                                "\"target/* **.txt\" -> direct descendants of target "
                                "and all .txt files")]
   [nil "--no-index" "Disable directory listings" :default false]
   [nil "--no-cache" "Disable cache headers" :default false]
   [nil "--no-compression" "Disable deflate and gzip compression" :default false]
   [nil "--follow-symlink" "Enable symbolic links support" :default false]
   [nil "--include-hidden" "Process hidden files as normal" :default false]
   [nil "--cors" (str "Support Acccess-Control-* headers, "
                      "see --cors-* options for more fine-grained control") :default false]
   [nil "--cors-origin" "Acccess-Control-Allow-Origin response header value" :default "*"]
   [nil "--cors-methods" "Acccess-Control-Allow-Methods response header value" :default "GET, POST"]
   [nil "--cors-allow-headers" "Acccess-Control-Allow-Headers response header value" :default nil]
   ["-h" "--help"]])

;; todo(kachayev): list of files to exclude
;; todo(kachayev): update pipeline with HttpObjectAggregator
;; todo(kachayev): range requests (we need latest Aleph changes to be merged)
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
      (try
        (let [port (if (not (empty? arguments))
                     (Integer/parseInt (first arguments))
                     (:port options))
              basic-auth (when-let [auth (:auth options)]
                           (parse-auth auth))
              bind-address (:bind options)
              compress? (not (:no-compression options))
              server (start (merge options {:basic-auth basic-auth
                                            :port port
                                            :compress? compress?}))]
          (log/infof "Serving HTTP on %s port %s" bind-address port)
          server)
        (catch Exception e
          (log/errorf "Something when wrong: %s" (.getMessage ^Exception e))
          (System/exit 1))))))
