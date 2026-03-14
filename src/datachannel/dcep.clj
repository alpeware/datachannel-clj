(ns datachannel.dcep
  (:require [datachannel.sctp :as sctp])
  (:import [java.nio ByteBuffer]))

(defn decode-message "TODO"
  [^bytes payload]
  (let [buf (ByteBuffer/wrap payload)
        msg-type (bit-and (.get buf) 0xff)]
    (if (= msg-type 3) ; DATA_CHANNEL_OPEN
      (let [channel-type-code (bit-and (.get buf) 0xff)
            priority (bit-and (.getShort buf) 0xffff)
            reliability (bit-and (.getInt buf) 0xffffffff)
            label-len (bit-and (.getShort buf) 0xffff)
            protocol-len (bit-and (.getShort buf) 0xffff)
            label-bytes (byte-array label-len)
            _ (.get buf label-bytes)
            protocol-bytes (byte-array protocol-len)
            _ (.get buf protocol-bytes)
            channel-type (get sctp/channel-type-map channel-type-code :reliable)
            ordered? (not (some #{channel-type} [:reliable-unordered :partial-reliable-unordered :partial-reliable-timed-unordered]))
            max-retransmits (if (some #{channel-type} [:partial-reliable :partial-reliable-unordered]) reliability nil)
            max-packet-life-time (if (some #{channel-type} [:partial-reliable-timed :partial-reliable-timed-unordered]) reliability nil)]
        {:type :open
         :channel-type channel-type
         :priority priority
         :reliability reliability
         :label (String. label-bytes "UTF-8")
         :protocol (String. protocol-bytes "UTF-8")
         :ordered ordered?
         :max-retransmits max-retransmits
         :max-packet-life-time max-packet-life-time})
      (if (= msg-type 2) ; DATA_CHANNEL_ACK
        {:type :ack}
        {:type :unknown}))))

(defn encode-message "TODO"
  [msg]
  (if (= (:type msg) :open)
    (let [label-bytes (.getBytes ^String (or (:label msg) "") "UTF-8")
          protocol-bytes (.getBytes ^String (or (:protocol msg) "") "UTF-8")
          label-len (alength label-bytes)
          protocol-len (alength protocol-bytes)
          ordered? (get msg :ordered true)
          max-retransmits (:max-retransmits msg)
          max-packet-life-time (:max-packet-life-time msg)
          channel-type (cond
                         (some? max-retransmits) (if ordered? :partial-reliable :partial-reliable-unordered)
                         (some? max-packet-life-time) (if ordered? :partial-reliable-timed :partial-reliable-timed-unordered)
                         :else (if ordered? :reliable :reliable-unordered))
          channel-type-code (get sctp/channel-types channel-type 0x00)
          priority (get msg :priority 256)
          reliability (or max-retransmits max-packet-life-time 0)
          buf (ByteBuffer/allocate (+ 12 label-len protocol-len))]
      (.put buf (unchecked-byte 3)) ; msg-type
      (.put buf (unchecked-byte channel-type-code))
      (.putShort buf (unchecked-short priority))
      (.putInt buf (unchecked-int reliability))
      (.putShort buf (unchecked-short label-len))
      (.putShort buf (unchecked-short protocol-len))
      (.put buf label-bytes)
      (.put buf protocol-bytes)
      (.array buf))
    (if (= (:type msg) :ack)
      (byte-array [(byte 2)])
      (byte-array 0))))
