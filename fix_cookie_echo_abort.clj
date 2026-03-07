(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/sctp_cookie_echo_abort_test.clj")
      new-code (str/replace code
                 #"(?s)\(is \(= 1 \(count \(:network-out result\)\)\)\)\n                \(let \[effect \(first \(:network-out result\)\)\]"
                 "(is (= 1 (count (:app-events result))))\n                (let [effect (first (:app-events result))]")]
  (spit "test/datachannel/sctp_cookie_echo_abort_test.clj" new-code))
