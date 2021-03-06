(ns cljam.pileup.mpileup
  (:require [clojure.string :as cstr]
            [clojure.java.io :refer [writer]]
            [clojure.tools.logging :as logging]
            [me.raynes.fs :as fs]
            [cljam.util.sam-util :as sam-util]
            [cljam.sequence :as cseq]
            [cljam.io :as io]
            [cljam.fasta :as fa]
            [cljam.pileup.common :refer [window-width step center]]
            [cljam.pileup.pileup :refer [rpositions]]))

(defn- pickup-qual [aln pos]
  (if (= (:qual aln) "*")
    \~
    (nth (:qual aln) pos \~)))

(defn- append-seq**
  [op target current]
  (case op
    \M (apply conj current (map str (:seq target)))
    \I (if (seq current)
         (update-in current [(dec (count current))]
                    vector
                    (str "+" (:n target) (apply str (:seq target))))
         current)
    \D (apply conj current (map str (:seq target)))
    \N (apply conj current (map str (:seq target)))
    current))

(defn encode-seq* [seq*]
  (loop [[f & r] (filter #(nil? (#{\P} (:op %))) seq*)
         ret     []
         op      nil
         tmp     {:n 0, :op nil, :seq nil}]
    (if (nil? f)
      (append-seq** op tmp ret)
      (if (nil? op)
        (recur r ret (:op f) f)
        (if (= (:op f) op)
          (recur r ret (:op f) (-> tmp
                                   (update-in [:n] + (:n f))
                                   (assoc :op (:op f))
                                   (update-in [:seq] (partial apply conj) (:seq f))))
          (let [new-ret (append-seq** op tmp ret)]
            (recur r new-ret (:op f) f)))))))

(defn- encode-seq
  "Encode sequence strings, returning string sequence for mpileup output.
  e.g. ({:n 2, :op \\M :seq [\\T \\A]} ... {:n 1, :op \\M :seq [\\C]})
       => (\"^?TA\" ... \"C$\")"
  [seq*]
  (let [seq** (encode-seq* seq*)]
    ;; NOTE: Disable for convenience for variant call
    ;; (-> (update-in seq** [(max 0 (dec (count seq**)))] str "$") ; Append "$" to the end
    ;;     (update-in [0] #(str "^?" %))) ; Insert "^?" before the begin
    seq**))

(defrecord ^:private PileupStatus [count seq qual])

(defn- ref-wrap
  "Modify sequence strings to referenced version.
  e.g. ({:n 2, :op \\M :seq [\\T \\A]} ...)
       => ({:n 2, :op \\M :seq [\\. \\.] :pos 7} ...)"
  [seq* ref-seq left]
  (let [positioned-seq (loop [ret     []
                              [f & r] seq*
                              pos     left]
                         (if (nil? f)
                           ret
                           (recur (conj ret (assoc f :pos pos))
                                  r
                                  (if-not (nil? (#{\M \D \N \= \X} (:op f)))
                                    (+ pos (count (:seq f)))
                                    pos))))]
    (map (fn [s]
           (if-not (nil? (#{\M \D \N \= \X} (:op s)))
             (update-in s [:seq] (partial map-indexed
                                          (fn [idx itm]
                                            (if (= itm (nth ref-seq (+ (dec (:pos s)) idx)))
                                              \.
                                              itm))))
             s))
         positioned-seq)))

(defn- count-for-alignment
  [aln ref-seq rname positions]
  (if (= rname (:rname aln))
    (let [left  (:pos aln)
          right (sam-util/get-end aln)
          seq** (cseq/parse (:seq aln) (:cigar aln))
          seq*  (encode-seq (if (nil? ref-seq)
                              seq**
                              (ref-wrap seq** ref-seq left)))]
      (map (fn [p]
             (if (<= left p right)
               (PileupStatus. 1 (nth seq* (- p left)) (pickup-qual aln (- p left)))
               (PileupStatus. 0 nil nil))) positions))
    (repeat (count positions) (PileupStatus. 0 nil nil))))

(defn- pickup-ref
  [ref-seq pos]
  (if (nil? ref-seq)
    \N
    (let [idx (dec pos)]
      (if (neg? idx)
        \N
        (if-let [ref (nth ref-seq idx)]
          ref
          \N)))))

(defn- count-for-positions
  [alns ref-line rname positions]
  (if (pos? (count alns))
    (let [cfas (map #(count-for-alignment % (:seq ref-line) rname positions) alns)
          plp1 (apply map (fn [& a]
                            {:rname rname
                             :count (reduce + (map :count a))
                             :seq   (->> (map :seq a)
                                         (filterv identity)
                                         (map #(cond
                                                 (vector? %) (second %)
                                                 :else %)))
                             :qual  (filterv identity (map :qual a))}) cfas)]
      (map #(assoc %2 :pos %1 :ref (pickup-ref (:seq ref-line) %1))
           positions plp1))
    (let [plp1 (repeat (count positions) {:rname rname
                                          :count 0
                                          :seq   nil
                                          :qual  nil})]
      (map #(assoc %2 :pos %1 :ref (pickup-ref (:seq ref-line) %1))
           positions plp1))))

(defn- read-alignments
  "Reads alignments which have the rname and are included in a range defined by
  the pos and window size, returning the alignments as a lazy seq. Reading
  depth is deep."
  [rdr rname rlength pos]
  (let [left (max (- pos window-width) 0)
        right (min rlength (+ pos window-width))]
    (io/read-alignments rdr {:chr rname
                             :start left
                             :end right
                             :depth :deep})))

(defn- read-ref-fasta-line
  ;; TODO Reduce memroy usage
  [fa-rdr rname]
  (if (nil? fa-rdr)
    nil
    (let [line (first
                (filter #(= (:rname %) rname) (fa/read fa-rdr)))]
      (fa/reset fa-rdr)
      line)))

(defn pileup*
  "Internal pileup function."
  [ref-line aln-rdr-fn rname start end]
  (->> (rpositions start end)
       (partition-all step)
       (map (fn [positions]
              (let [pos (if (= (count positions) step)
                          (nth positions center)
                          (nth positions (quot (count positions) 2)))
                    alns (aln-rdr-fn pos)]
                (count-for-positions alns ref-line rname positions))))
       flatten))

(defn pileup
  ([bam-reader rname]
   (pileup nil bam-reader rname -1 -1))
  ([fa-rdr bam-reader rname]
   (pileup fa-rdr bam-reader rname -1 -1))
  ([fa-rdr bam-reader rname start end]
   (try
     (if-let [r (sam-util/ref-by-name (io/read-refs bam-reader) rname)]
       (pileup* (read-ref-fasta-line fa-rdr rname)
                (partial read-alignments bam-reader rname (:len r))
                rname
                (if (neg? start) 0 start)
                (if (neg? end) (:len r) end)))
     (catch bgzf4j.BGZFException _
       (throw (RuntimeException. "Invalid file format"))))))

;; Writing
;; -------

(defn- write-line!
  [^java.io.BufferedWriter w line]
  (.write w (cstr/join \tab [(:rname line)
                             (:pos line)
                             (:ref line)
                             (:count line)
                             (cstr/join (:seq line))
                             (cstr/join (:qual line))]))
  (.newLine w))

(defn create-mpileup
  "Creates a mpileup file from the BAM file."
  [f fa-rdr bam-reader]
  (try
    (with-open [w (writer f)]
      (doseq [rname (map :name (io/read-refs bam-reader))]
        (doseq [line (pileup fa-rdr bam-reader rname)]
          (when-not (zero? (:count line))
            (write-line! w line)))))
    (catch Exception e (do
                         (fs/delete f)
                         (logging/error "Failed to create mpileup")
                         (throw e)))))
