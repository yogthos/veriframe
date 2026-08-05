;; Vendored from jolt-lang/ring-chez-adapter @07f14d9 and NOT ours to relicense,
;; so this file carries no veriframe copyright notice. Upstream is EPL-2.0,
;; which is what this project uses, so the vendored copy is redistributable on
;; the same terms as the rest of the tree.
;;
;; It had no licence at all when this copy was taken, which meant all rights
;; reserved and no permission to redistribute it. That is why the check happened.
;;
;; The VERIFRAME-marked change below is the only local modification.

(ns ring-chez.adapter
  "A Ring adapter for jolt: a minimal HTTP/1.1 server over BSD sockets, bound
  directly through jolt.ffi (no jolt built-in, no JVM). Synchronous Ring 1.x
  handlers. Serves loopback (127.0.0.1).

      (require '[ring-chez.adapter :as adapter])
      (def server (adapter/run-server my-handler {:port 3000}))
      ;; ... later ...
      (adapter/stop-server server)

  VENDORED from jolt-lang/ring-chez-adapter @07f14d9 with one change, marked
  `VERIFRAME` below: the accept loop hands each connection to a future instead
  of serving it inline. Upstream serves one connection at a time on the accept
  thread, so a POST /v1/chat/completions running a multi-minute beam blocks
  /health and every other request until it finishes. Worth offering upstream;
  vendored until then. See PLAN.md, \"Observability and intervention\"."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))

;; The libc/socket symbols are declared in deps.edn (:jolt/native :process) and
;; loaded by jolt before this namespace is required, so the bindings resolve.

;; accept/recv/send may block — :blocking emits them collect-safe so a parked
;; accept thread never pins the garbage collector.
(ffi/defcfn c-socket     "socket"     [:int :int :int] :int)
(ffi/defcfn c-bind       "bind"       [:int :pointer :int] :int)
(ffi/defcfn c-listen     "listen"     [:int :int] :int)
(ffi/defcfn c-setsockopt "setsockopt" [:int :int :int :pointer :int] :int)
(ffi/defcfn c-close      "close"      [:int] :int)
(ffi/defcfn c-accept     "accept"     [:int :pointer :pointer] :int :blocking)
(ffi/defcfn c-recv       "recv"       [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-send       "send"       [:int :pointer :size_t :int] :ssize_t :blocking)

(def ^:private AF-INET 2)
(def ^:private SOCK-STREAM 1)
(def ^:private macos?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))
;; SOL_SOCKET / SO_REUSEADDR differ by platform: macOS 0xffff / 4, Linux 1 / 2.
(def ^:private sol-socket  (if macos? 0xffff 1))
(def ^:private so-reuse    (if macos? 4 2))

;; sockaddr_in for 127.0.0.1:port. macOS: byte0 = sin_len (16), byte1 = family;
;; Linux: bytes0-1 = family (little-endian, so byte0 = AF_INET).
(defn- make-sockaddr [port]
  (let [sa (ffi/alloc 16)]
    (dotimes [i 16] (ffi/write sa :uint8 i 0))
    (if macos?
      (do (ffi/write sa :uint8 0 16) (ffi/write sa :uint8 1 AF-INET))
      (ffi/write sa :uint8 0 AF-INET))
    (ffi/write sa :uint8 2 (bit-and (bit-shift-right port 8) 0xff))   ; port hi (network order)
    (ffi/write sa :uint8 3 (bit-and port 0xff))                       ; port lo
    (ffi/write sa :uint8 4 127) (ffi/write sa :uint8 5 0)             ; 127.0.0.1
    (ffi/write sa :uint8 6 0)   (ffi/write sa :uint8 7 1)
    sa))

(defn- listen-socket [port]
  (let [fd (c-socket AF-INET SOCK-STREAM 0)]
    (when (neg? fd) (throw (ex-info "socket() failed" {})))
    (let [opt (ffi/alloc 4)]
      (ffi/write opt :int 0 1)
      (c-setsockopt fd sol-socket so-reuse opt 4)
      (ffi/free opt))
    (let [sa (make-sockaddr port)]
      (when (neg? (c-bind fd sa 16))
        (c-close fd) (ffi/free sa) (throw (ex-info (str "bind() failed on port " port) {})))
      (ffi/free sa))
    (when (neg? (c-listen fd 64)) (c-close fd) (throw (ex-info "listen() failed" {})))
    fd))

;; --- request reading --------------------------------------------------------
(def ^:private bufsize 65536)

(defn- content-length [text hdr-end]
  (let [hdrs (str/lower-case (subs text 0 hdr-end))
        i (str/index-of hdrs "content-length:")]
    (if-not i
      0
      (let [s (+ i (count "content-length:"))
            e (loop [j s] (if (or (>= j (count hdrs))
                                  (= \return (nth hdrs j)) (= \newline (nth hdrs j))) j (recur (inc j))))]
        (or (parse-long (str/trim (subs hdrs s e))) 0)))))

;; read a full request (headers + Content-Length body) into a string, or nil.
(defn- read-request [conn]
  (let [buf (ffi/alloc bufsize)]
    (try
      (loop [acc ""]
        (let [n (c-recv conn buf bufsize 0)]
          (if (<= n 0)
            (when (pos? (count acc)) acc)
            (let [acc (str acc (ffi/read-bytes buf n))
                  hdr-end (str/index-of acc "\r\n\r\n")]
              (cond
                (nil? hdr-end) (recur acc)
                (>= (- (count acc) (+ hdr-end 4)) (content-length acc hdr-end)) acc
                :else (recur acc))))))
      (finally (ffi/free buf)))))

;; --- request -> Ring map ----------------------------------------------------
(defn- request->ring [text port]
  (let [blank (str/index-of text "\r\n\r\n")
        head (if blank (subs text 0 blank) text)
        body (if blank (subs text (+ blank 4)) "")
        lines (str/split head #"\r\n")
        parts (str/split (or (first lines) "GET / HTTP/1.1") #" ")
        method (or (first parts) "GET")
        target (or (second parts) "/")
        qi (str/index-of target "?")
        [uri qs] (if qi [(subs target 0 qi) (subs target (inc qi))] [target nil])
        headers (reduce (fn [m line]
                          (let [i (str/index-of line ":")]
                            (if (and i (pos? i))
                              (assoc m (str/lower-case (str/trim (subs line 0 i))) (str/trim (subs line (inc i))))
                              m)))
                        {} (rest lines))]
    {:server-port    port
     :server-name    "127.0.0.1"
     :remote-addr    "127.0.0.1"
     :uri            uri
     :query-string   qs
     :scheme         :http
     :request-method (keyword (str/lower-case method))
     :protocol       "HTTP/1.1"
     :headers        headers
     :body           (when (pos? (count body)) (java.io.StringReader. body))}))

;; --- Ring response -> the response string -----------------------------------
(def ^:private status-text
  {200 "OK" 201 "Created" 204 "No Content" 301 "Moved Permanently" 302 "Found"
   303 "See Other" 304 "Not Modified" 400 "Bad Request" 401 "Unauthorized"
   403 "Forbidden" 404 "Not Found" 405 "Method Not Allowed" 500 "Internal Server Error"})

(defn- body->string [b]
  (cond (nil? b) ""
        (string? b) b
        (or (seq? b) (vector? b)) (apply str b)
        ;; a File / InputStream / Reader body (ring's resource + file responses):
        ;; read its contents rather than printing the object.
        :else (try (slurp b) (catch Throwable _ (str b)))))

(defn- response->string [resp]
  (let [status (or (:status resp) 200)
        body (body->string (:body resp))
        ;; Content-Length is the body's octet count. ring-defaults'
        ;; wrap-content-length already sets it (as UTF-8 bytes); honor that
        ;; and only compute when absent, so we never stamp a second, conflicting
        ;; Content-Length. Connection: close also delimits the response.
        len (or (->> (:headers resp)
                     (some (fn [[k v]]
                             (let [kn (str/lower-case (if (keyword? k) (name k) (str k)))]
                               (when (= kn "content-length") v)))))
                (alength (.getBytes body "UTF-8")))
        sb (StringBuilder.)]
    (.append sb (str "HTTP/1.1 " status " " (get status-text status "OK") "\r\n"))
    (doseq [[k v] (:headers resp)]
      (let [kn (str/lower-case (if (keyword? k) (name k) (str k)))]
        (when (not= kn "content-length")
          (.append sb (str (if (keyword? k) (name k) (str k)) ": " v "\r\n")))))
    (.append sb (str "Content-Length: " len "\r\n"))
    (.append sb "Connection: close\r\n\r\n")
    (.append sb body)
    (.toString sb)))

(defn- send-all [conn s]
  (let [buf (ffi/alloc (max 1 (* 4 (count s))))     ; UTF-8 worst case 4 bytes/char
        n (ffi/write-bytes buf s)]
    (loop [off 0]
      (when (< off n)
        (let [sent (c-send conn (+ buf off) (- n off) 0)]
          (when (pos? sent) (recur (+ off sent))))))
    (ffi/free buf)))

;; --- the accept loop --------------------------------------------------------
;; Clean shutdown: stop-server closes the listen fd (which unblocks accept) and
;; clears `running?`; the loop then exits instead of spinning on the dead fd.
;; VERIFRAME: one connection's full lifecycle, extracted so the accept loop can
;; hand it to a worker instead of running it inline.
(defn- serve-conn [conn handler port]
  (try
    (try
      (when-let [text (read-request conn)]
        (send-all conn (response->string (handler (request->ring text port)))))
      (catch Throwable _e
        (try (send-all conn (response->string {:status 500
                                               :headers {"Content-Type" "text/plain"}
                                               :body "Internal Server Error"}))
             (catch Throwable _ nil))))
    (finally (c-close conn))))

(defn- serve-loop [listen-fd handler port running?]
  (loop []
    (let [conn (c-accept listen-fd ffi/null ffi/null)]
      (cond
        (not @running?) nil
        (neg? conn) (when @running? (recur))
        :else
        (do
          ;; VERIFRAME: thread per connection. The accept loop returns to
          ;; accept immediately, so a slow handler can't stall the server.
          (future (serve-conn conn handler port))
          (recur))))))

(defn run-server
  "Start the server; return a handle {:socket :port :running}. The accept loop
  runs on a background thread; the handler is a synchronous Ring handler. opts:
  :port (default 3000)."
  [handler opts]
  (let [port (get opts :port 3000)
        fd (listen-socket port)
        running? (atom true)]
    (future (serve-loop fd handler port running?))
    {:socket fd :port port :running running?}))

(defn stop-server
  "Stop the server: unblock + exit the accept loop and close the listen socket."
  [server]
  (reset! (:running server) false)
  (c-close (:socket server))
  nil)
