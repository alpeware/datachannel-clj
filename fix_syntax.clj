(require '[clojure.string :as str])
(let [code (slurp "src/datachannel/core.clj")
      new-code (str/replace code #"\)\)\)\)\)\)\)\n\)\)\n\n\n\(defn- run-loop" ")))))))\n\n\n(defn- run-loop")]
  (spit "src/datachannel/core.clj" new-code))
