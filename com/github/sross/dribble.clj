(ns com.github.sross.dribble
  (:use clojure.contrib.duck-streams)
  (:import clojure.lang.LineNumberingPushbackReader
           [java.io Reader Writer OutputStreamWriter FileWriter]))


(defmacro #^{:private true} returning [[var val] & body]
  `(let [~var ~val]
     (do ~@body)
     ~var))

(defn #^{:private true}  echo-reader [reader & outs]
  "Returns a new Echo Reader which echoes all output read to the utputa"
  (proxy [java.io.Reader] ()
    (close [] (.close reader))
    (mark [int] (.mark reader int))
    (read ([] (returning [byte (.read reader)]
                (when (pos? byte) (dorun (map #(.write % byte) outs)))))
          ([arg1] (returning [byte (.read reader arg1)]
                    (when (pos? byte)
                      (dorun (map #(.write % arg1 0 byte) outs)))))
          ([buf off len] (returning [bytes-read (.read reader buf off len)]
                           (when (pos? bytes-read)
                             (dorun (map #(.write % buf off bytes-read) outs))))))
    (ready [] (.ready reader))
    (reset [] (.reset reader))
    (skip [l]  (.skip reader l))))

(defn #^{:private true}  broadcast-writer [& writers]
  (proxy [java.io.Writer] ()
    (append ([x] (dorun (map #(.append % x) writers)))
            ([c s e] (dorun (map #(.append % c s e) writers))))
    (close [] (dorun (map #(.close %) writers)))
    (flush [] (dorun (map #(.flush %) writers)))
    (write ([b] (dorun (map #(.write % b) writers)))
           ([b o l] (dorun (map #(.write % b o l) writers))))))


(def #^{:private true}  *dribbles* (atom ()))

(defn- start-dribbling [out]
  (let [file-out (writer out)
        new-out (broadcast-writer *out* file-out)
        new-in (LineNumberingPushbackReader. (echo-reader *in* file-out))]
    (dosync
     (swap! *dribbles* conj [file-out new-out new-in *out* *in*])
     (alter-var-root #'*in* (constantly new-in))
     (alter-var-root #'*out* (constantly new-out)))
    true))

(defn- stop-dribbling []
  (when-not (empty? @*dribbles*)
    (dosync
     (let [[file-out _ _ prev-out prev-in] (first @*dribbles*)]
       (.close file-out)
       (swap! *dribbles* rest)
       (alter-var-root #'*in* (constantly prev-in))
       (alter-var-root #'*out* (constantly prev-out))
       true))))

(defn dribble
  "Sends a record of all input & output to OUT, which must be a suitable argument to
clojure.contrib.duck-streams/writer. This can be called many times.
Calling dribble with no arguments terminates the most recent transcript.
Currently only *in* and *out* are redirected and *err* remains untouched."
  ([] (stop-dribbling))
  ([out] (start-dribbling out)))