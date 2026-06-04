(ns regesta.cli
  "Command-line entry point wrapping `regesta.convert` (WP-8) — the last step from
   library to runnable tool. Converts a source document to a target serialisation:
   the converted document goes to **stdout**, the ADR 0015 loss report to **stderr**.

     regesta convert <input-file> --from <fmt> --to <fmt> [--record-id <id>] [--out <file>]
     regesta formats
     regesta --help

   `run` is pure — it parses args, does the conversion and returns
   `{:exit :out :err}` without printing or exiting — so tests drive it directly;
   `-main` is the thin shell that prints the streams and calls `System/exit`."
  (:require [clojure.string :as str]
            [regesta.convert :as convert]
            [regesta.loss-report :as lr]))

(def ^:private usage
  (str/join
   "\n"
   ["regesta — documentary-metadata conversion through the LRMoo pivot"
    ""
    "Usage:"
    "  regesta convert <input-file> --from <fmt> --to <fmt> [--record-id <id>] [--out <file>]"
    "  regesta formats          list the supported source and target formats"
    "  regesta --help"
    ""
    "  --from        source format (a spoke)"
    "  --to          target serialisation"
    "  --record-id   record id for single-record spokes (Dublin Core; default: from the filename)"
    "  --out         write the output to a file instead of stdout"
    ""
    "The converted document is written to stdout; the loss report goes to stderr."]))

(defn- parse-args
  "Parse `args` into {:command :positional [..] :flags {str str} :help?}.
   `--flag value` pairs become flags; the first bare token is the command, the
   rest positionals."
  [args]
  (loop [as args m {:flags {}}]
    (if (empty? as)
      m
      (let [a (first as)]
        (cond
          (#{"--help" "-h"} a)      (recur (rest as) (assoc m :help? true))
          (str/starts-with? a "--") (recur (drop 2 as) (assoc-in m [:flags (subs a 2)] (second as)))
          (:command m)              (recur (rest as) (update m :positional (fnil conj []) a))
          :else                     (recur (rest as) (assoc m :command a)))))))

(defn- ->record-id [s]
  (let [s     (str/replace-first s #"^:" "")
        [a b] (str/split s #"/" 2)]
    (if b (keyword a b) (keyword a))))

(defn- file-stem [path]
  (-> path (str/replace #".*/" "") (str/replace #"\.[^.]*$" "") (str/replace #"[^A-Za-z0-9]+" "-")))

(defn- fmt-line [label fs] (str label (str/join " " (sort (map name fs)))))

(defn- do-convert [{:strs [from to record-id out]} input]
  (cond
    (not (and from to input))
    {:exit 2 :out "" :err (str "convert needs <input-file> --from <fmt> --to <fmt>\n\n" usage)}

    (not (.exists (java.io.File. ^String input)))
    {:exit 2 :out "" :err (str "input file not found: " input)}

    :else
    (try
      (let [rid    (cond record-id      (->record-id record-id)
                         (= from "dc")   (keyword "doc" (file-stem input))
                         :else           nil)
            result (convert/convert {:from   (keyword from)
                                     :to     (keyword to)
                                     :source (slurp input)
                                     :opts   (cond-> {} rid (assoc :record-id rid))})
            report (str (lr/format-conversion-report (:loss result))
                        "\n(" (:records result) " record"
                        (when (not= 1 (:records result)) "s") " converted)")]
        (if out
          (do (spit out (:output result)) {:exit 0 :out "" :err (str "wrote " out "\n" report)})
          {:exit 0 :out (:output result) :err report}))
      (catch clojure.lang.ExceptionInfo e
        {:exit 2 :out "" :err (str "error: " (.getMessage e) " " (pr-str (ex-data e)))})
      (catch Exception e
        {:exit 2 :out "" :err (str "error: " (.getMessage e))}))))

(defn run
  "Pure CLI core: parse `args`, run the conversion, return `{:exit :out :err}`.
   Never prints, never exits."
  [args]
  (let [{:keys [command help? flags positional]} (parse-args args)]
    (cond
      (or help? (nil? command)) {:exit 0 :out "" :err usage}
      (= command "formats")     {:exit 0 :out ""
                                 :err (str (fmt-line "from: " (convert/source-formats)) "\n"
                                           (fmt-line "to:   " (convert/target-formats)))}
      (= command "convert")     (do-convert flags (first positional))
      :else                     {:exit 2 :out "" :err (str "unknown command: " command "\n\n" usage)})))

(defn -main [& args]
  (let [{:keys [exit out err]} (run (vec args))]
    (when (seq out) (print out) (flush))
    (when (seq err) (binding [*out* *err*] (print err) (flush)))
    (System/exit exit)))
