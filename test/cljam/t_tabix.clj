(ns cljam.t-tabix
  (:require [midje.sweet :refer :all]
            [cljam.t-common :refer :all]
            [cljam.tabix :as tbi]))

(facts "about read-index"
  (fact "done without errors"
    (tbi/read-index test-tabix-file) => anything)
  (let [index (tbi/read-index test-tabix-file)]
    (fact "returns a map"
      index => map?)
    (fact "check the returning map's structure"
      index => (just {:n-seq number?
                      :preset number?
                      :sc number?
                      :bc number?
                      :ec number?
                      :meta number?
                      :skip number?
                      :seq vector?
                      :bin-index vector?
                      :linear-index vector?}))))

(with-state-changes [(before :facts (prepare-cavia!))]
  (fact "large file" :slow :heavy
    (tbi/read-index test-large-tabix-file) => anything))
