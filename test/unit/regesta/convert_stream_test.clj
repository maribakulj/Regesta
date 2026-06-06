(ns regesta.convert-stream-test
  "Tests for streaming conversion (WP-7 / DoD #6, `regesta.convert/convert-stream`):
   it produces the *same* output and loss report as batch `convert`, and folds the
   loss report in memory bounded by the corpus size — so a record stream of any
   length runs in constant working set. Exercised on the real 560-record BIB-R
   MARC corpus (and cycled for the scale checks)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.convert :as convert]
            [regesta.spokes :as spokes]))

(defn- slurp-gz [path]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream path))]
    (slurp in)))

(def ^:private marc-src (slurp-gz "test/fixtures/bibr-gold/bibrcat_marc21.xml.gz"))
(def ^:private raw (:records ((:importer (spokes/plugin :marc21)) {} marc-src)))

(defn- collect-stream [request]
  (let [out (atom [])
        res (convert/convert-stream request #(swap! out conj %))]
    (assoc res :output (str/join "\n" @out))))

(deftest streamed-conversion-equals-batch
  (testing "convert-stream emits the same documents and the same loss as batch convert"
    (let [batch  (convert/convert {:from :marc21 :to :ntriples :source marc-src})
          strm   (collect-stream {:from :marc21 :to :ntriples :records raw})]
      (is (= 560 (:records batch) (:records strm)))
      (is (= (:output batch) (:output strm)))                       ; identical serialisation
      (testing "the streamed loss report equals the batch one minus the batch-only :distinct-losses"
        (is (= (dissoc (:loss batch) :distinct-losses) (:loss strm)))
        (is (contains? (:loss batch) :distinct-losses))             ; batch has it
        (is (not (contains? (:loss strm) :distinct-losses)))))))    ; stream (bounded) does not

(deftest the-loss-accumulator-is-bounded-in-the-corpus-size
  (testing "five cycles of the corpus: the report's *size* is invariant, only counts scale"
    (let [noop  (fn [_])
          one   (convert/convert-stream {:from :marc21 :to :ntriples :records raw} noop)
          five  (convert/convert-stream {:from :marc21 :to :ntriples
                                         :records (take (* 5 560) (cycle raw))} noop)]
      (is (= (* 5 560) (:records five)))
      (testing "the distinct-field set (the accumulator's footprint) does not grow with N"
        (is (= (get-in one [:loss :source-fields]) (get-in five [:loss :source-fields])))
        (is (= (keys (get-in one [:loss :by-category])) (keys (get-in five [:loss :by-category])))))
      (testing "loss *counts* scale exactly with the corpus (same records repeated)"
        (is (= (* 5 (get-in one [:loss :total])) (get-in five [:loss :total])))))))

(deftest streams-a-large-corpus-to-completion
  (testing "a lazy stream far larger than any fixture runs through (held one record at a time)"
    (let [n   (* 20 560)                                            ; 11 200 records, lazily
          cnt (atom 0)
          res (convert/convert-stream {:from :marc21 :to :ntriples
                                       :records (take n (cycle raw))}
                                      (fn [_] (swap! cnt inc)))]
      (is (= n (:records res) @cnt))
      (is (pos? (get-in res [:loss :total]))))))

(deftest convert-stream-guards-unknown-formats
  (is (thrown? clojure.lang.ExceptionInfo
               (convert/convert-stream {:from :bogus :to :ntriples :records raw} (fn [_]))))
  (is (thrown? clojure.lang.ExceptionInfo
               (convert/convert-stream {:from :marc21 :to :bogus :records raw} (fn [_])))))
