(ns db-fuzzer.mutations)

;; Integer Bit flips:
(defn replace-random-bit [integer]
  "Flip a random bit"
  (let [len (count (Integer/toBinaryString integer))]
    (bit-flip integer (rand-int len))))

;;sequential order bit flip functions, step over of 1 bit:
(defn one-bit-int-flip [integer]
  "Flips the first bit"
  (bit-flip integer 0))

(defn two-bit-int-flip [integer]
  "Flip two bits in a row"
  (bit-flip (one-bit-int-flip integer) 2))

(defn four-bit-int-flip [integer]
  "Flip fours bits in a row"
  (bit-flip (bit-flip (two-bit-int-flip integer) 3) 4))

(defn bit-flip-all [integer]
  "Flips all bits in string"
  (let [bin-int (Integer/toBinaryString integer)]
    (Integer/parseInt (.replaceAll (.replaceAll (.replaceAll bin-int "1" "x") "0" "1") "x" "0") 2)))

(defn bit-shifts [integer]
  "Shift bits left or right"
  (if (= 0 (rand-int 2))
    (bit-shift-left integer 1)
    (bit-shift-right integer 1)))

(defn simple-arithmetics []
  ";;attempts to subtly increment or decrement existing integer values. The experimentally chosen range for the operation is -35 to +35;
  the stage consists of three separate operations.
   1) creates precision integer
   2) creates negative integer
   3) creates negative precision integer"
  (list
    (rand 35)
    (- (rand-int 35))
    (- (rand 35))
    ))

(defn replace-known-integers []
  ";; relies on a hardcoded set of integers chosen for their demonstrably elevated likelihood of triggering edge conditions in
  typical code (e.g., -1, 256, 1024, MAX_INT-1, MAX_INT, infinity, NaN, 231-1, ect). The fuzzer uses a stepover of one byte to sequentially overwrite\n
  existing data in the input file with one of the approximately two dozen \"interesting\" values, using both endians (the writes are 8-, 16-, and 32-bit wide)\n"

  (list -128 127 255 -32768 32767 65535 -8388608 8388607 16777215 -2147483648 2147483647 Integer/MIN_VALUE Integer/MAX_VALUE
        (unchecked-negate (Long/MIN_VALUE)) (unchecked-inc Integer/MAX_VALUE) 4294967295 (bigint 263) (bigint -263))
  )




;; Block functions operate on entire value

;; (defn block-deletions
;;"deletes the entire (or part of the) value" [s])

;;(defn block-memset)
;; Block duplication via overwrite or insertion,
;; Block memset.


;; String mutations

;;Walking byte flips: constant step over of one byte:
(defn one-bit-flip [byte-arr]
  (apply str (map char (assoc byte-arr 0 (one-bit-int-flip (first byte-arr)))))
  )

(defn two-bit-flip [byte-arr]
  (apply str (map char (assoc byte-arr 0 (two-bit-int-flip (first byte-arr)))))
  )

(defn four-bit-flip [byte-arr]
  (apply str (map char (assoc byte-arr 0 (four-bit-int-flip (first byte-arr)))))
  )

(defn one-byte-flip [byte-arr]
  "Flip 8 bits in a row"
  (apply str (map char (assoc byte-arr 0 (bit-flip-all (first byte-arr)))))
  )

(defn two-byte-flip [byte-arr]
  "Flip 16 bits in a row"
  (apply str (map char (assoc (assoc byte-arr 0 (bit-flip-all (first byte-arr))) 1 (bit-flip-all (get byte-arr 2)))))
  )

(defn four-byte-flip [byte-arr]
  "Flip 32 bits in a row"
  (apply str (map char (assoc (assoc (assoc (assoc byte-arr 0 (bit-flip-all (get byte-arr 0))) 1 (bit-flip-all (get byte-arr 1))) 2 (bit-flip-all (get byte-arr 2))) 3 (bit-flip-all (get byte-arr 3)))))
  )

;; Random Byte mutations:
(defn shuffle-bytes [byte-arr]
  "Randomly rearrange input bytes"
  (apply str (map char (shuffle byte-arr)))
  )

(defn replace-random-byte [byte-arr]
  "replace byte with random one"
  (apply str (map char (assoc byte-arr (rand-int 4) (rand-int 65510))))
  )

(defn insert-random-byte [byte-arr]
  "Increase size by one random byte"
  (apply str (map char (conj byte-arr (rand-int 65510))))
  )

(defn insert-two-random-bytes [byte-arr]
  "Increase size by two random bytes"
  (apply str (map char (conj (conj byte-arr (rand-int 65510)) (rand-int 65510))))
  )

(defn erase-random-byte [byte-arr]
  "Reduce size by removing a random byte"
  (apply str (map char
                  (let [len (count byte-arr)
                        position (rand-int len)]
                    (concat (subvec byte-arr 0 position)
                            (subvec byte-arr (inc position)))))))

(defn insert-repeated-byte [byte-arr]
  "Increase size by adding at least 3 random bytes"
  (let [repeated-byte (rand-int 65510)]
    (apply str (map char (conj (conj (conj byte-arr (char repeated-byte)) (char repeated-byte)) (char repeated-byte)))))
  )


(defn integer-mutations [integer]
  (list
    (Integer/toBinaryString integer)
    (replace-random-bit integer)
    (one-bit-int-flip integer)
    (two-bit-int-flip integer)
    (four-bit-int-flip integer)
    (bit-flip-all integer)
    (bit-shifts integer)
    (simple-arithmetics)
    (replace-known-integers))
  )

(defn string-mutations [string]
  (let [byte-arr (into [] (map int (.getBytes string)))]
    (list
    (println byte-arr)
    (one-bit-flip byte-arr)
    (two-bit-flip byte-arr)
    (four-bit-flip byte-arr)
    (one-byte-flip byte-arr)
    (two-byte-flip byte-arr)
    (four-byte-flip byte-arr)
    (shuffle-bytes byte-arr)
    (replace-random-byte byte-arr)
    (insert-random-byte byte-arr)
    (insert-two-random-bytes byte-arr)
    (erase-random-byte byte-arr)
    (insert-repeated-byte byte-arr))

    ))