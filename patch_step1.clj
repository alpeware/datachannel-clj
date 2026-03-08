(ns patch-step1
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-process-chunk-data []
  (let [search-str "(defmethod process-chunk :data [state chunk packet now-ms]
  (let [proto (:protocol chunk)
        tsn (:tsn chunk)
        s1 (if (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int (get state :remote-tsn -1)))))
             (assoc state :remote-tsn tsn)
             state)
        sack-chunk {:type :sack
                    :cum-tsn-ack (:remote-tsn s1)
                    :a-rwnd 100000
                    :gap-blocks []
                    :duplicate-tsns []}
        s1 (update s1 :pending-control-chunks conj sack-chunk)]
    (cond
      (= proto :webrtc/dcep)
      (let [payload (:payload chunk)
            msg-type (bit-and (aget ^bytes payload 0) 0xff)]
        (if (= msg-type 3) ;; OPEN
          (let [ack-tsn (:next-tsn s1)
                s2 (update s1 :next-tsn inc)
                ack-ssn (:ssn s2)
                s3 (update s2 :ssn inc)
                ack-chunk {:type :data
                           :flags 3 ;; B and E bits
                           :tsn ack-tsn
                           :stream-id (:stream-id chunk)
                           :seq-num ack-ssn
                           :protocol :webrtc/dcep
                           :payload (byte-array [(byte 2)])}]
            ;; For now, putting ack chunk directly into streams
            {:next-state (assoc-in s3 [:streams (:stream-id chunk) :send-queue]
                                   (conj (get-in s3 [:streams (:stream-id chunk) :send-queue] [])
                                         {:tsn ack-tsn :chunk ack-chunk :sent-at now-ms :retries 0 :sent? false}))
             :next-events []})
          {:next-state s1 :next-events []}))
      :else
      {:next-state s1 :next-events [{:type :on-message :payload (:payload chunk) :stream-id (:stream-id chunk) :protocol proto}]})))"

        replace-str "(defmethod process-chunk :data [state chunk packet now-ms]
  (let [proto (:protocol chunk)
        tsn (:tsn chunk)
        s1 (if (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int (get state :remote-tsn -1)))))
             (assoc state :remote-tsn tsn)
             state)
        sack-chunk {:type :sack
                    :cum-tsn-ack (:remote-tsn s1)
                    :a-rwnd 100000
                    :gap-blocks []
                    :duplicate-tsns []}
        s1 (update s1 :pending-control-chunks conj sack-chunk)
        stream-id (:stream-id chunk)
        recv-q (get-in s1 [:streams stream-id :recv-queue] [])
        new-recv-q (conj recv-q chunk)
        s2 (assoc-in s1 [:streams stream-id :recv-queue] new-recv-q)]
    {:next-state s2 :next-events []}))"]
    (if (str/includes? core-content search-str)
      (str/replace core-content search-str replace-str)
      (do
        (println "Warning: Could not find process-chunk :data to patch.")
        core-content))))

(defn apply-patch []
  (let [content (patch-process-chunk-data)]
    (spit core-path content)))

(apply-patch)
