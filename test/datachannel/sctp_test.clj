(ns datachannel.sctp-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as tc-gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [datachannel.sctp :as sctp])
  (:import [java.nio ByteBuffer]))

;; Helper to compare byte arrays
(defn bytes= [b1 b2]
  (if (and (nil? b1) (nil? b2))
    true
    (if (or (nil? b1) (nil? b2))
      false
      (java.util.Arrays/equals ^bytes b1 ^bytes b2))))

;; Specs

(s/def ::src-port (s/int-in 0 65536))
(s/def ::dst-port (s/int-in 0 65536))
(s/def ::verification-tag int?)
(s/def ::checksum int?)

(s/def ::init-tag int?)
(s/def ::a-rwnd int?)
(s/def ::outbound-streams (s/int-in 0 65536))
(s/def ::inbound-streams (s/int-in 0 65536))
(s/def ::initial-tsn int?)
(s/def ::params map?)

(s/def ::cookie bytes?)

(s/def ::tsn int?)
(s/def ::stream-id (s/int-in 0 65536))
(s/def ::seq-num (s/int-in 0 65536))
(s/def ::protocol #{:webrtc/dcep :webrtc/string :webrtc/binary :webrtc/empty-string :webrtc/empty-binary 50 51 53 56 57})
(s/def ::payload bytes?)
(s/def ::unordered boolean?)
(s/def ::beginning boolean?)
(s/def ::ending boolean?)

(s/def ::cum-tsn-ack int?)
(s/def ::gap-blocks (s/coll-of (s/tuple (s/int-in 0 65536) (s/int-in 0 65536))))
(s/def ::duplicate-tsns (s/coll-of int?))

(defmulti chunk-type :type)

(defmethod chunk-type :init [_]
  (s/keys :req-un [::init-tag ::a-rwnd ::outbound-streams ::inbound-streams ::initial-tsn ::params]))

(defmethod chunk-type :init-ack [_]
  (s/keys :req-un [::init-tag ::a-rwnd ::outbound-streams ::inbound-streams ::initial-tsn ::params]))

(defmethod chunk-type :cookie-echo [_]
  (s/keys :req-un [::cookie]))

(defmethod chunk-type :cookie-ack [_]
  (s/keys :req [:datachannel.sctp/type]))

(defmethod chunk-type :data [_]
  (s/keys :req-un [::tsn ::stream-id ::seq-num ::protocol ::payload]
          :opt-un [::unordered ::beginning ::ending]))

(defmethod chunk-type :sack [_]
  (s/keys :req-un [::cum-tsn-ack ::a-rwnd ::gap-blocks ::duplicate-tsns]))

(defmethod chunk-type :heartbeat [_]
  (s/keys :req-un [::params]))

(defmethod chunk-type :heartbeat-ack [_]
  (s/keys :req-un [::params]))

(defmethod chunk-type :default [_]
  map?)

(s/def ::chunk (s/multi-spec chunk-type :type))

(s/def ::chunks (s/coll-of ::chunk))

(s/def ::packet (s/keys :req-un [::src-port ::dst-port ::verification-tag ::chunks]
                        :opt-un [::checksum]))

;; Generators

(def byte-array-gen (tc-gen/fmap byte-array (tc-gen/vector (tc-gen/fmap byte (tc-gen/choose -128 127)))))

(defn data-flags [{:keys [unordered beginning ending]}]
  (bit-or (if unordered 4 0)
          (if beginning 2 0)
          (if ending 1 0)))

(def uint32-gen (tc-gen/large-integer* {:min 0 :max 4294967295}))

(def chunk-gen
  (tc-gen/one-of
   [(tc-gen/hash-map :type (tc-gen/return :init)
                  :init-tag uint32-gen
                  :a-rwnd uint32-gen
                  :outbound-streams (tc-gen/choose 0 65535)
                  :inbound-streams (tc-gen/choose 0 65535)
                  :initial-tsn uint32-gen
                  :params (tc-gen/return {}))
    (tc-gen/hash-map :type (tc-gen/return :init-ack)
                  :init-tag uint32-gen
                  :a-rwnd uint32-gen
                  :outbound-streams (tc-gen/choose 0 65535)
                  :inbound-streams (tc-gen/choose 0 65535)
                  :initial-tsn uint32-gen
                  :params (tc-gen/return {}))
    (tc-gen/hash-map :type (tc-gen/return :cookie-echo)
                  :cookie byte-array-gen)
    (tc-gen/hash-map :type (tc-gen/return :cookie-ack))
    (tc-gen/fmap (fn [data] (assoc data :flags (data-flags data)))
              (tc-gen/hash-map :type (tc-gen/return :data)
                            :tsn uint32-gen
                            :stream-id (tc-gen/choose 0 65535)
                            :seq-num (tc-gen/choose 0 65535)
                            :protocol (tc-gen/elements [:webrtc/string :webrtc/binary])
                            :payload byte-array-gen
                            :unordered tc-gen/boolean
                            :beginning tc-gen/boolean
                            :ending tc-gen/boolean))
    (tc-gen/hash-map :type (tc-gen/return :sack)
                  :cum-tsn-ack uint32-gen
                  :a-rwnd uint32-gen
                  :gap-blocks (tc-gen/vector (tc-gen/tuple (tc-gen/choose 0 65535) (tc-gen/choose 0 65535)))
                  :duplicate-tsns (tc-gen/vector uint32-gen))
    (tc-gen/hash-map :type (tc-gen/return :heartbeat)
                  :params (tc-gen/return {}))
    (tc-gen/hash-map :type (tc-gen/return :heartbeat-ack)
                  :params (tc-gen/return {}))]))

(def packet-gen
  (tc-gen/hash-map :src-port (tc-gen/choose 0 65535)
                :dst-port (tc-gen/choose 0 65535)
                :verification-tag uint32-gen
                :chunks (tc-gen/vector chunk-gen)))

;; Tests

(defn round-trip [packet]
  (let [buf (ByteBuffer/allocate 65536)
        _ (sctp/encode-packet packet buf)
        _ (.flip buf)]
    (sctp/decode-packet buf)))

;; Deep compare function that handles byte arrays and specific fields
(defn packet= [p1 p2]
  (and (= (dissoc p1 :chunks :checksum) (dissoc p2 :chunks :checksum))
       (= (count (:chunks p1)) (count (:chunks p2)))
       (every? true?
               (map (fn [c1 c2]
                      (and (= (:type c1) (:type c2))
                           (case (:type c1)
                             :data (and (= (dissoc c1 :payload :flags :length) (dissoc c2 :payload :flags :length))
                                        (bytes= (:payload c1) (:payload c2)))
                             :cookie-echo (bytes= (:cookie c1) (:cookie c2))
                             ;; For other types, we ignore flags and length as they are transport artifacts
                             (= (dissoc c1 :flags :length) (dissoc c2 :flags :length)))))
                    (:chunks p1)
                    (:chunks p2)))))

(defspec packet-encoding-decoding-test 100
  (prop/for-all [packet packet-gen]
    (let [decoded (round-trip packet)]
      (packet= packet decoded))))

(deftest manual-data-chunk-test
  (let [data-chunk {:type :data
                    :tsn 12345
                    :stream-id 1
                    :seq-num 10
                    :protocol :webrtc/string
                    :payload (.getBytes "Hello" "UTF-8")
                    :unordered false
                    :beginning true
                    :ending true
                    :flags 3}
        packet {:src-port 5000
                :dst-port 5000
                :verification-tag 999
                :chunks [data-chunk]}
        decoded (round-trip packet)
        decoded-chunk (first (:chunks decoded))]

    (is (= (:tsn data-chunk) (:tsn decoded-chunk)))
    (is (= "Hello" (String. ^bytes (:payload decoded-chunk) "UTF-8")))
    (is (true? (:beginning decoded-chunk)))
    (is (true? (:ending decoded-chunk)))
    (is (false? (:unordered decoded-chunk)))))
