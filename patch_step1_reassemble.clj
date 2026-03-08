(ns patch-step1-reassemble
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(def reassemble-fn "
(defn assemble-payload [chunks]
  (let [total-len (reduce + (map #(alength ^bytes (:payload %)) chunks))
        result (byte-array total-len)]
    (loop [cs chunks
           offset 0]
      (if (empty? cs)
        result
        (let [c (first cs)
              p (:payload c)
              len (alength ^bytes p)]
          (System/arraycopy p 0 result offset len)
          (recur (rest cs) (+ offset len)))))))

(defn reassemble-stream [stream-data]
  (let [q (:recv-queue stream-data [])
        sorted-q (sort-by :tsn q)]
    (loop [remaining sorted-q
           current-msg []
           new-q []
           app-events []
           next-ssn (get stream-data :next-ssn 0)]
      (if (empty? remaining)
        {:new-stream (assoc stream-data :recv-queue new-q :next-ssn next-ssn) :app-events app-events}
        (let [chunk (first remaining)
              flags (:flags chunk)
              u-bit? (pos? (bit-and flags 4))
              b-bit? (pos? (bit-and flags 2))
              e-bit? (pos? (bit-and flags 1))]
          (if u-bit?
            ;; Unordered
            (if (and b-bit? e-bit?)
              ;; Single chunk Unordered
              (recur (rest remaining)
                     []
                     new-q
                     (conj app-events {:type :on-message :payload (:payload chunk) :stream-id (:stream-id chunk) :protocol (:protocol chunk)})
                     next-ssn)
              (if b-bit?
                ;; Start of fragmented Unordered
                (recur (rest remaining)
                       [chunk]
                       new-q
                       app-events
                       next-ssn)
                (if e-bit?
                  ;; End of fragmented Unordered
                  (let [full-msg (conj current-msg chunk)]
                    (recur (rest remaining)
                           []
                           new-q
                           (conj app-events {:type :on-message :payload (assemble-payload full-msg) :stream-id (:stream-id chunk) :protocol (:protocol chunk)})
                           next-ssn))
                  ;; Middle of fragmented Unordered
                  (recur (rest remaining)
                         (conj current-msg chunk)
                         new-q
                         app-events
                         next-ssn))))
            ;; Ordered
            (if (= (:seq-num chunk) next-ssn)
              (if (and b-bit? e-bit?)
                ;; Single chunk Ordered
                (recur (rest remaining)
                       []
                       new-q
                       (conj app-events {:type :on-message :payload (:payload chunk) :stream-id (:stream-id chunk) :protocol (:protocol chunk)})
                       (inc next-ssn))
                (if b-bit?
                  ;; Start of fragmented Ordered
                  (recur (rest remaining)
                         [chunk]
                         new-q
                         app-events
                         next-ssn)
                  (if e-bit?
                    ;; End of fragmented Ordered
                    (let [full-msg (conj current-msg chunk)]
                      (recur (rest remaining)
                             []
                             new-q
                             (conj app-events {:type :on-message :payload (assemble-payload full-msg) :stream-id (:stream-id chunk) :protocol (:protocol chunk)})
                             (inc next-ssn)))
                    ;; Middle of fragmented Ordered
                    (recur (rest remaining)
                           (conj current-msg chunk)
                           new-q
                           app-events
                           next-ssn))))
              ;; Not expected SSN, hold in queue
              (recur (rest remaining)
                     current-msg
                     (conj new-q chunk)
                     app-events
                     next-ssn))))))))

(defn reassemble [state app-events]
  (let [streams (:streams state)]
    (loop [stream-ids (keys streams)
           current-state state
           current-events app-events]
      (if (empty? stream-ids)
        {:new-state current-state :app-events current-events}
        (let [stream-id (first stream-ids)
              stream-data (get-in current-state [:streams stream-id])
              {:keys [new-stream app-events:stream]} (reassemble-stream stream-data)
              events-with-dcep (reduce (fn [acc event]
                                         (if (= (:protocol event) :webrtc/dcep)
                                           ;; DCEP handling should ideally happen earlier or generate next-state
                                           ;; but to keep it simple we emit :dcep-message and handle it in wrapper
                                           ;; Let's inline DCEP ack response here to state
                                           acc
                                           (conj acc event)))
                                       []
                                       app-events:stream)
              dcep-events (filter #(= (:protocol %) :webrtc/dcep) app-events:stream)
              state-with-dcep-acks (reduce (fn [st evt]
                                             (let [payload (:payload evt)
                                                   msg-type (bit-and (aget ^bytes payload 0) 0xff)]
                                               (if (= msg-type 3) ;; OPEN
                                                 (let [ack-tsn (:next-tsn st)
                                                       s2 (update st :next-tsn inc)
                                                       ack-ssn (:ssn s2)
                                                       s3 (update s2 :ssn inc)
                                                       ack-chunk {:type :data
                                                                  :flags 3 ;; B and E bits
                                                                  :tsn ack-tsn
                                                                  :stream-id (:stream-id evt)
                                                                  :seq-num ack-ssn
                                                                  :protocol :webrtc/dcep
                                                                  :payload (byte-array [(byte 2)])}]
                                                   (assoc-in s3 [:streams (:stream-id evt) :send-queue]
                                                             (conj (get-in s3 [:streams (:stream-id evt) :send-queue] [])
                                                                   {:tsn ack-tsn :chunk ack-chunk :sent-at 0 :retries 0 :sent? false})))
                                                 st)))
                                           (assoc-in current-state [:streams stream-id] new-stream)
                                           dcep-events)]
          (recur (rest stream-ids)
                 state-with-dcep-acks
                 (into current-events events-with-dcep)))))))
")

(defn add-reassemble []
  (let [insert-pos (str/index-of core-content "(defn handle-sctp-packet")]
    (str (subs core-content 0 insert-pos)
         reassemble-fn
         "\n"
         (subs core-content insert-pos))))

(defn patch-handle-sctp-packet [content]
  (let [search-str "                    (recur next-state
                           (rest remaining-chunks)
                           (into app-events next-events)))))]
      (packetize (:new-state res) (:app-events res))))"
        replace-str "                    (recur next-state
                           (rest remaining-chunks)
                           (into app-events next-events)))))]
      (let [reassembled (reassemble (:new-state res) (:app-events res))]
        (packetize (:new-state reassembled) (:app-events reassembled))))))"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find handle-sctp-packet to patch.")
        content))))

(defn apply-patch []
  (let [content (add-reassemble)
        content2 (patch-handle-sctp-packet content)]
    (spit core-path content2)))

(apply-patch)
