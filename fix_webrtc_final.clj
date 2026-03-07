(require '[clojure.string :as str])
(let [code (slurp "src/datachannel/core.clj")
      new-code (str/replace code
                 #"\(let \[t \(\:next-tsn \@state\)\]"
                 "(let [t (or (:next-tsn @state) 0)]")]
  (let [new-code (str/replace new-code #"\(let \[s \(\:ssn \@state\)\]" "(let [s (or (:ssn @state) 0)]")]
    (let [new-code (str/replace new-code #"\(\:remote-ver-tag \@state\)" "(or (:remote-ver-tag @state) 0)")]
      (let [new-code (str/replace new-code #"\(:next-tsn \@state\)" "(or (:next-tsn @state) 0)")]
        (let [new-code (str/replace new-code #"\(:ssn \@state\)" "(or (:ssn @state) 0)")]
          (spit "src/datachannel/core.clj" new-code))))))
