(ns db-fuzzer.mungers
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.test :refer [deftest is run-tests] :as test]
   [couplet.core :as couplet]
   [db-fuzzer.rent-an-atom :as rent-an-atom]))

(defn replace-at [string pos char]
  (let [split (split-at pos string)
        lhs (first split)
        rhs (rest (second split))]
    (apply str (concat lhs [char] rhs))))

(deftest test-replace-at
  (is (= (replace-at "an_underscore" 2 " ") "an underscore")))

(def homoglyph-map
  {" " " ᅟᅠ         　ㅤ"
   "!" "!ǃ！"
   "$" "$＄"
   "%" "%％"
   "&" "&＆"
   "(" "(﹝（"
   ")" ")﹞）"
   "*" "*⁎＊"
   "+" "+＋"
   "," ",‚，"
   "-" "-‐𐆑－"
   "." ".٠۔܁܂․‧。．｡"
   "/" "/̸⁄∕╱⫻⫽／ﾉ"
   "1" "1Iا１"
   "2" "2２"
   "3" "3３"
   "4" "4４"
   "5" "5５"
   "6" "6６"
   "7" "7𐒇７"
   "8" "8Ց８"
   "9" "9９"
   ":" ":։܃܄∶꞉："
   "" ";；"
   "<" "<‹＜"
   "=" "=𐆐＝"
   ">" ">›＞"
   "?" "?？"
   "@" "@＠"
   "[" "[［"
   "\\" "\\＼"
   "]" "]］"
   "^" "^＾"
   "_" "_＿"
   "`" "`｀"
   "a" "AaÀÁÂÃÄÅàáâãäåɑΑαаᎪＡａ"
   "b" "BbßʙΒβВЬᏴᛒＢｂ"
   "c" "CcϲϹСсᏟⅭⅽ𐒨Ｃｃ"
   "d" "DdĎďĐđԁժᎠḍⅮⅾＤｄ"
   "e" "EeÈÉÊËéêëĒēĔĕĖėĘĚěΕЕеᎬＥｅ"
   "f" "FfϜＦｆ"
   "g" "GgɡɢԌնᏀＧｇ"
   "h" "HhʜΗНһᎻＨｈ"
   "i" "IilɩΙІіاᎥᛁⅠⅰ𐒃Ｉｉ"
   "j" "JjϳЈјյᎫＪｊ"
   "k" "KkΚκКᏦᛕKＫｋ"
   "l" "LlʟιاᏞⅬⅼＬｌ"
   "m" "MmΜϺМᎷᛖⅯⅿＭｍ"
   "n" "NnɴΝＮｎ"
   "0" "0OoΟοОоՕ𐒆Ｏｏ"
   "o" "Oo0ΟοОоՕ𐒆Ｏｏ"
   "p" "PpΡρРрᏢＰｐ"
   "q" "QqႭႳＱｑ"
   "r" "RrʀԻᏒᚱＲｒ"
   "s" "SsЅѕՏႽᏚ𐒖Ｓｓ"
   "t" "TtΤτТᎢＴｔ"
   "u" "UuμυԱՍ⋃Ｕｕ"
   "v" "VvνѴѵᏙⅤⅴＶｖ"
   "w" "WwѡᎳＷｗ"
   "x" "XxΧχХхⅩⅹＸｘ"
   "y" "YyʏΥγуҮＹｙ"
   "z" "ZzΖᏃＺｚ"
   "{" "{｛"
   "|" "|ǀا｜"
   "}" "}｝"
   "~" "~⁓～"})

(defn munge-homoglyphic-unicode [s]
  (let [candidates
        (->>
         (map-indexed (fn [idx itm] [idx (str itm)]) s)
         (filter (fn [[idx itm]] (contains? (set (keys homoglyph-map)) itm)))
         )
        candidate (when candidates (rand-nth candidates))
        replacement (rand-nth (get homoglyph-map (second candidate)))
        ]
    (replace-at s (first candidate) replacement)))

(defn munge-rand-char-ascii [s]
  (let [split (split-at (rand-int (count s)) s)
        lhs (first split)
        rhs (rest (second split))]
    (apply str (concat lhs [(char (rand-int 255))] rhs))))

(defn munge-rand-char-unicode [s]
  (let [split (split-at (rand-int (count s)) s)
        lhs (first split)
        rhs (rest (second split))]
    (apply str (concat lhs [(couplet/to-str
                             (gen/sample
                              (s/gen :couplet.core/codepoint)
                              (inc (rand-int 5))))]
                       rhs))))

;; wrap-munge is middleware for your :value context hooks.
;; It's not in the standard pattern of middleware due to some Clojure limitations
;; around eval'ing partial functions.
(defn wrap-munge [f introspection {:keys [shared-state-token] :as context}]
  (if (and shared-state-token
           (= 0 (rand-int 1)) ;; only try ot munge sometimes so that munging happens throughout the query
           (rent-an-atom/try-get-munge-permission! shared-state-token))
    (if (= 0 (rand-int 1)) ;; if we will munge, do a homoglyphic replacement half the time
      (munge-homoglyphic-unicode (f introspection context))
      (munge-rand-char-unicode (f introspection context)))
    (f introspection context)))

(defn whitespace-swap
  "Randomly picks a standard ASCII 32 whitespace and swaps it with a unicode whitespace."
  [s]
  (let [unicode-whitespaces [160 8192 8193 8194 8195 8196 8197 8198 8199 8200 8201 8202 8232 8287 12288] ;; See https://emptycharacter.com/ for a good reference on unicode whitespaces
        whitespace-positions
        (->> (map-indexed (fn [idx itm] [idx itm]) s) ;; assign a position to each letter
         (filter (fn [[idx itm]] (= itm (char 32)))) ;; filter down to just the spaces
         (map first)) ;; grab the positions
        chosen-whitespace-position
        (when (not (empty? whitespace-positions)) (rand-nth whitespace-positions))
        ]
    (if chosen-whitespace-position
      (replace-at s chosen-whitespace-position (couplet/to-str [(rand-nth unicode-whitespaces)]))
      s)))

(deftest test-whitespace-swap
  (is (= (whitespace-swap "cat") "cat"))
  (is (not (= (whitespace-swap "the cat in the hat") "the cat in the hat"))))
