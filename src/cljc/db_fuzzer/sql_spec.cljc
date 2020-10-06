(ns db-fuzzer.sql-spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [instaparse.core :as insta]
            [clojure.test :refer [deftest is run-tests] :as test]
            [next.jdbc :as jdbc]
            [db-fuzzer.platform-specific-behavior :as platform-specific-behavior]
            [db-fuzzer.rent-an-atom :as rent-an-atom]
            [db-fuzzer.mungers :as mungers]
            [db-fuzzer.mutations :as mutate]
            [couplet.core :as couplet]))

;; Utility fns
(defn rand-set-element
  "Picks a number of elements at random from a set.
  E.g. (generate/rand-set-element 5 #{:a :b :c}) -> (:c :c :a :b :a))"
  ([set-coll]
   (first (rand-set-element 1 set-coll)))
  ([n set-coll]
   (repeatedly n (fn [] (rand-nth (vec set-coll)))))
  ([n set-coll exclude-coll]
   (repeatedly n (fn [] (rand-nth (vec (clojure.set/difference set-coll exclude-coll)))))))

(defn spans
  "From the instaparse guide https://github.com/engelberg/instaparse .
   Useful to print out the parsing spans from an intsaparse parse result."
  [t]
  (if (sequential? t)
    (cons (insta/span t) (map spans (next t)))
    t))

(defn sample
  "Runs function f n times and returns a list of the results."
  [n f]
  (take n (repeatedly f)))

(defn fn-has-known-arity
  "Pass in a function for f. You may have to do (var-get (find-var (symbol f-name))) if f-name is a string with your function name."
  [f arity]
  (not (empty? (filter #(= % arity) (map count (:arglists (meta f)))))))

;; ------------------------------------------
;; Pre-defined low-level generation functions
;;
;; When generating from a BNF, you'll often find that the BNF gets into unneeded (for our purposes)
;; detail about what tokens are allowed, etc.
;; Terminal functions in your BNF will correspond to Clojure functions, so there's no need to define a rule
;; in BNF if you provide a generator function here.
;; 
;; ------------------------------------------

(defn identifier
  "TODO not actually following the SQL BNF exactly here "
  ([context-hooks introspection {:keys [parent table] :as context}]
   (identifier)) ;; TODO happily ignoring context right now. Could be using the context to do a lookup
  ([]
   (as-> (gen/generate (s/gen string?)) $
     (cond
       (re-matches #"^[0-9].*" $) (str "a" $) ;; sql table names can't be solely numeric or empty
       (empty? $) "a"
       :default $))))

(defn unsigned-integer []
  (Math/abs (gen/generate (s/gen int?))))

(defn length []
  (rand-int 256)) ;; MySQL's max column length is 255, so this generates 0-255. Apparantly a length of 0 is accepted by MySQL.

(defn bit-length []
  (rand-int 65))

(defn precision []
  (rand-int 65))

(defn time-precision []
  (rand-int 7))

(defn timestamp-precision []
  (rand-int 7))

(defn float-precision []
  (rand-int 23))

(defn decimal-scale []
  (rand-int 31))

(defn numeric-scale []
  (rand-int 31))

;; --------------------------------------------------------
;; Generate the ebnf-parser so that we can read the sql bnf
;;
;; ebnf-parser is a BNF parser built using instaparse.
;; Instaparse requires a BNF string to define the language you want to parse,
;; which in this case happens to be BNF. Pretty meta, right?
;;
;; I've implemented the parts of BNF I've needed below.
;; Please note that there are a few quirks, like the lack of whitespace in the alternation rule.
;; Please also note that, despite being used to formally define grammars, the grammar of BNF
;; is actually quite poorly defined and there are several conventions in use.
;; If you wish to use BNF from a file you may need to do some formatting before it works here.
;;
;; The ebnf-parser take in a bnf and outputs the corresponding AST.
;; Example usage:
;; db-fuzzer.sql-spec> (def full-ast (ebnf-parser \"character_string_type = ('character')|('varchar'['a']);\"))
;; [:grammar
;;  [:rule
;;   \"character_string_type\"
;;   [:alternation
;;    [:grouping [:terminal \"character\"]]
;;    [:grouping
;;     [:concatenation
;;      [:terminal \"varchar\"]
;;      [:optional [:terminal \"a\"]]]]]]]
;; --------------------------------------------------------

(def ebnf-parser
  (insta/parser
   "grammar = rule+
    rule = (lhs *{<whitespace>} (<'='>|<':='>|<'::='>) *{<whitespace>} rhs *{<whitespace>} <';'>) [<'\n'>][<whitespace>]+
    <lhs> = identifier
    <rhs> = identifier | terminal | optional | repetition | grouping | alternation | concatenation
    alternation = rhs <'|'> rhs
    terminal = <\"'\"> #'[a-zA-Z0-9_(),. */+-=]+' <\"'\">
    optional = <'['> *{<whitespace>} rhs *{<whitespace>} <']'>
    repetition = <'{'> rhs <'}'>
    grouping = <'('> rhs <')'>
    concatenation = rhs (<','> | <whitespace>) *{<whitespace>} rhs
    whitespace = #'\\s+'
    <identifier> = #'[a-zA-Z_-]+'
    "))

(deftest ebnf-parser-tests
  (let [valid? (fn [s]
                 (not (insta/failure? (ebnf-parser s))))]
    (is (valid? "a = b;"))
    (is (valid? "a = b|c;"))
    ;;(is (valid? "a = b |c;")) ;; Note: I took out support for whitespace in alternation since it was leading the parser to parse any whitespace as a separate, empty rule.
    ;;(is (valid? "a = b | c;")) ;; same as above line
    (is (valid? "a = b,c;"))
    (is (valid? "a = b, c;"))
    (is (valid? "a = b ,c;"))
    (is (valid? "a = b , c;"))
    (is (valid? "a = b c;"))
    (is (valid? "a  =  b  c;"))))

;; -----------------------------------------------------------------------
;; Our sql BNFs to use for sql statement generation.
;; Note that since gen-from-ast uses late-binding via eval,
;; there's no order necessary for specifying the rules, either within
;; each section of BNF or between the sections themselves.
;; Simply make sure that you generate the functions for each rule before
;; you start using the generators. (See generate-fns below).
;; -----------------------------------------------------------------------
(def sql-module-bnf
  "qualified-local-table-name = local-table-name;
  local-table-name = qualified-identifier;
  qualified-identifier = identifier;
  table-element-list = '(' table-element {[',' table-element]} ')';
  table-element = column-definition;
  column-definition = column-name ' ' data-type;
  column-name = identifier;")

(def datatype-bnf
  "scale ::= unsigned-integer;
  data-type ::= json-type|character-string-type|national-character-string-type|bit-string-type|numeric-type|datetime-type;
  json-type = 'json';
  character-string-type ::= ('character'|'char'|'character varying'|'char varying'|'varchar') '(' length ')';
  bit-string-type ::= ('bit'),['(' bit-length ')'];
  national-character-string-type ::= ('national character'|'national char'|'nchar') ([ [' varying'] '(' length ')']);
  numeric-type = exact-numeric-type|approximate-numeric-type;
  exact-numeric-type = ('numeric',['(' precision [',' numeric-scale] ')'])|('decimal',['(' precision [',' decimal-scale] ')'])|('dec',['(' precision [',' decimal-scale] ')'])|'integer'|'int'|'smallint';
  approximate-numeric-type = ('float',['(' float-precision ')'])|'real'|'double';
  datetime-type = 'date'|('time',['(' time-precision ')'])|('timestamp',['(' timestamp-precision ')']);
  time-fractional-seconds-precision = unsigned-integer;
  interval-type = 'interval ' interval-qualifier;
  interval-qualifier = (start-field ' to ' end-field)|single-datetime-field;
  start-field = non-second-datetime-field['(' interval-leading-field-precision ')'];
  non-second-datetime-field = 'year'|'month'|'day'|'hour'|'minute';
  interval-leading-field-precision = unsigned-integer;
  end-field = non-second-datetime-field|'second'['(' interval-fractional-seconds-precision ')'];
  interval-fractional-seconds-precision = unsigned-integer;
  single-datetime-field = (non-second-datetime-field['(' interval-leading-field-precision ')'])|('second'['(' interval-leading-field-precision[',' interval-fractional-seconds-precision] ')']);
  domain-name = qualified-name;
  qualified-name = [schema-name '.'] qualified-identifier;
  schema-name = [catalog-name '.'] unqualified-schema-name;
  catalog-name = identifier;
  unqualified-schema-name = identifier;" )

(def create-table-bnf
  "table-definition = 'create table if not exists ' table-name-unaliased table-element-list;
  table-name-unaliased = identifier;
  table-name = identifier [' as ' sql-alias];
  sql-alias = identifier;")
;;table-name = qualified-name|qualified-local-table-name;

;; (def search-condition-bnf
;;   "search-condition = boolean-term|search-condition-or-term;
;;   search-condition-or-term = (search-condition ' or ' boolean-term);
;;   boolean-term = boolean-factor|boolean-term-and-term;
;;   boolean-term-and-term = (boolean-term ' and ' boolean-factor);
;;   boolean-factor= [' not '] boolean-test;
;;   boolean-test = boolean-primary [' is ' [' not '] truth-value];
;;   truth-value = ' true '|' false '|' unknown ';
;;   boolean-primary = predicate;
;;   search-condition-parens = ('(' search-condition ')');
;;   predicate = comparison-predicate;
;;   comparison-predicate = row-value-constructor;
;;   row-value-constructor = row-value-constructor-element;
;;   row-value-constructor-element = value-expression;
;;   value-expression = numeric-value-expression;
;;   numeric-value-expression = term|term|term|(numeric-value-expression ' + ' term)|(numeric-value-expression ' - ' term);
;;   term = factor|factor|factor|(term ' * ' factor)|(term ' / ' factor);
;;   factor = ['+'|'-'] numeric-primary;
;;   numeric-primary = value-expression-primary;
;;   value-expression-primary = unsigned-value-specification;
;;   unsigned-value-specification = unsigned-integer;
;;   ")

(def search-condition-bnf
  "search-condition = column-name ' = ' column-name;
  search-condition-or-term = (search-condition ' or ' boolean-term);
  boolean-term = boolean-factor|boolean-term-and-term;
  boolean-term-and-term = (boolean-term ' and ' boolean-factor);
  boolean-factor= [' not '] boolean-test;
  boolean-test = boolean-primary [' is ' [' not '] truth-value];
  truth-value = ' true '|' false '|' unknown ';
  boolean-primary = predicate;
  search-condition-parens = ('(' search-condition ')');
  predicate = comparison-predicate;
  comparison-predicate = row-value-constructor;
  row-value-constructor = row-value-constructor-element;
  row-value-constructor-element = value-expression;
  value-expression = numeric-value-expression;
  numeric-value-expression = term|term|term|(numeric-value-expression ' + ' term)|(numeric-value-expression ' - ' term);
  term = factor|factor|factor|(term ' * ' factor)|(term ' / ' factor);
  factor = ['+'|'-'] numeric-primary;
  numeric-primary = value-expression-primary;
  value-expression-primary = unsigned-value-specification;
  unsigned-value-specification = unsigned-integer;
  ")

(def select-query-bnf
  "test-select = select;
  select = 'select ' [set-quantifier] select-list table-expression;
  table-expression = from-clause;
  from-clause = ' from ' table-reference;
  table-reference = table-name|joined-table;
  table-reference-left = table-name;
  table-reference-right = table-name;
  joined-table = ' (' qualified-join ') ';
  qualified-join = natural-join|specified-join;
  natural-join = table-reference-left ' natural join ' table-reference-right;
  specified-join = table-reference-left join-type ' join ' table-reference-right join-specification;
  join-specification = join-condition;
  join-condition = ' on ' search-condition;
  join-type = ' inner '|((' left'|' right') ' outer '); 
  select-list = ' * '|(select-sublist [{', ' select-sublist}]) ;
  select-sublist = column-name;
  set-quantifier = ' distinct '|' all ';")

(defn- lift-alternations-one-level [rhs-ast]
  (if (and (vector? rhs-ast)
           (= (first rhs-ast) :alternation))
    (vec (apply concat
                [(first rhs-ast)]
                (map (fn [child]
                       (if (and (vector? child)
                                (= (first child) :alternation))
                         (vec (rest child))
                         [child]))
                     (rest rhs-ast))))
    rhs-ast))

(defn lift-alternations
  "Due to the way the BNF is parsed, a rule such as:
     (ebnf-parser \"a = b|c|d;\")
  is parsed to
  [:grammar [:rule \"a\" [:alternation \"b\" [:alternation \"c\" \"d\"]]]]
  
  The problem here is that the default behavior for alternation is to pick things 50/50.
  Since the alternations are nested, the actual distribution would be:
    b 50%
    c 25%
    d 25%
  This issue becomes more acute the more options for alternation there are on each line.

  This unnecessary nesting also prevents :alternation context hooks from easily picking between all the choices.

  To fix this, lift-alternations will transform
    [:alternation \"b\" [:alternation \"c\" \"d\"]]
  to
    [:alternation \"b\" \"c\" \"d\"]]
  "
  [rhs-ast]
  (clojure.walk/postwalk lift-alternations-one-level rhs-ast))

(deftest lift-alternations-test
  (is (= (lift-alternations [:alternation :b [:alternation :c :d]])
         [:alternation :b :c :d]))
  (is (= (lift-alternations [:alternation [:alternation :c [:alternation :d :e]] :f])
         [:alternation :c :d :e :f])))

(defn lift-alternations-in-grammar
  "Removes unnecessary nested alternations in a grammar.
  E.g.
    [:grammar
      [:rule \"scale\" \"unsigned-integer\"]
      [:rule
        \"data-type\"
        [:alternation
          \"character-string-type\"
          [:alternation
            \"national-character-string-type\"
            [:alternation
              \"bit-string-type\"
                [:alternation \"numeric-type\" \"datetime-type\"]]]]]
  becomes
    [:grammar
     [:rule \"scale\" \"unsigned-integer\"]
     [:rule
       \"data-type\"
       [:alternation
         \"character-string-type\"
         \"national-character-string-type\"
         \"bit-string-type\"
         \"numeric-type\"
         \"datetime-type\"]]]

  See lift-alternations for more background on the issue that this solves.
"
  [ast]
  (assert (= :grammar (first ast)))
  (into [:grammar]
        (map (fn [rule]
               (let [rule-name (get rule 1)]
                 (vec (concat [:rule rule-name]
                              (lift-alternations (rest (rest rule)))))))
             (rest ast))))

;; ----------------
;; Context Hooks
;; ----------------
;; BNF allows you to specify context-free grammar.
;; Using the BNF for SQL we can generate syntactically correct SQL statements.
;; Unfortunately, being syntactically correct does not imply being contextually correct.
;; For example, in SELECT column-name FROM table-name, column-name must be a column in table table-name.
;; To avoid issues with queries failing due to reasons like this, we must add context into our generator functions
;; Context hooks will let us do this.
;;
;; There are several types of context hooks.
;; The simplest is the :value context hook.
;; Each :value context hook is associated with a rule.
;; When the hook is present, the rule execute its :value hook function rather than continuing normal AST-based evaluation.
;; A example of a value hook in use is the column-name rule. Sometimes we need column-name to be a column belonging to a certain table.
;; Other times (for example, when no introspection object is based in) we need to simply generate a random column name.
;;
;; How does a :value context hook know what the context is?
;; Via the context map of course!!
;; The context map contains two types of data:
;; -- query global data: this is stored externally.
;;    Reference the :shared-state-token and use functions like read-shared-state to read this shared state.
;;    An example use of this is for generating query-unique table alias-names.
;; -- AST-node-and-below data: this is stored directly in the context map. Basically any key besides :shared-state-token.
;;    This data is set by a rules :mutate value hook function and passes context to its constituent elements.
;;    For example, the "select" rule picks a table and puts it into the context for all it's downstream children to
;;    access and use (so that things like column-name can pick a column from the chosen table.
;;
;; There's another type of context hook called :alternation context hooks.
;; Consider the following BNF rule for table-reference:
;;  table-reference = table-name|(' (' joined-table ') ');
;; This rule is represent in our AST as:
;;  [:rule
;;   "table-reference"
;;   [:alternation
;;    "table-name"
;;    [:grouping
;;      [:concatenation
;;      [:terminal " ("]
;;      [:concatenation "joined-table" [:terminal ") "]]]]]]
;; When it evaluates the :alternation, it will pick a table-name half the time and a joined table the other
;; half of the time. For our purposes, we'd rather have more simple table names then joins.
;; :alternation context hooks let us do this.
;; 
;; In this example :alternation hook, the choices get weighter 70/30 rather than 50/50.
;; (fn [introspection context choices]
;;   (if (< (rand) 0.7)
;;     (first choices)
;;     (second choices)))

(defn common-mutate [{:keys [shared-state-token munge] :as context}]
  (if (and shared-state-token munge)
    (do (rent-an-atom/swap-shared-state! shared-state-token #(assoc % :munge munge))
        (dissoc context munge))
    context))

(def context-hooks
  {:mutate
   {"select" (fn [introspection context]
               (let [context (common-mutate context)
                     table (rand-nth (keys (:tables introspection)))
                     column (rand-nth (keys (get (:tables introspection) table)))]
                 (merge context {:table table
                                 :table-alias (rent-an-atom/get-next-alias! (:shared-state-token context))
                                 :column column
                                 :parent "select"})))
    "joined-table" (fn [introspection {:keys [table table-alias] :as context}]
                     (->
                      (merge context {:alias-table-names true
                                      :table-lhs (or table (rand-nth (keys (:tables introspection))))
                                      :table-lhs-alias (or table-alias (rent-an-atom/get-next-alias! (:shared-state-token context)))
                                      :table-rhs (rand-nth (keys (:tables introspection)))
                                      :table-rhs-alias (rent-an-atom/get-next-alias! (:shared-state-token context))                                     })
                      (dissoc :table :table-alias) ;; clear out the table from the select statement so that we re-use its alias only once, and not multiple times as would be the case in nested joins.
                      ))}
   :value
   {"column-name" (fn [introspection context]
                    (mungers/wrap-munge (fn [introspection {:keys [table-alias column skip-table-lookup] :as context}]
                                  (if (and table-alias column)
                                    (str (name table-alias) "." (name column))
                                    ;; if we didn't get passed in a table alias via the context, pick a table and column ourselves rather than hitting a NullPointerException
                                    (if skip-table-lookup
                                      (identifier)
                                      (let [table (rand-nth (keys (get-in introspection [:tables])))
                                            column (rand-nth (keys (get-in introspection [:tables table])))]
                                        (str (name table) ". " (name column))))))
                                introspection context))
    "table-name" (fn [introspection context]
                   (mungers/wrap-munge (fn [introspection {:keys [table table-alias] :as context}]
                                 (str (name table)
                                      (str " as " (name table-alias))))
                               introspection context))
    "table-reference-left" (fn [introspection {:keys [table-lhs table-lhs-alias] :as context}]
                             (str (name table-lhs) " as " (name table-lhs-alias)))
    "table-reference-right" (fn [introspection {:keys [table-rhs table-rhs-alias] :as context}]
                              (str (name table-rhs) " as " (name table-rhs-alias)))
    "search-condition" (fn [introspection {:keys [table-lhs table-lhs-alias table-rhs table-rhs-alias] :as context}]
                         (let [lhs-column (name (rand-nth (keys (get-in introspection [:tables table-lhs]))))
                               rhs-column (name (rand-nth (keys (get-in introspection [:tables table-rhs]))))]
                           (str (name table-lhs-alias) "." lhs-column
                                (rand-set-element #{"=" "<=" "<" ">" ">="})
                                (name table-rhs-alias) "." rhs-column)))}
   :alternation
   {"table-reference" (fn [introspection context choices]
                        (if (< (rand) 0.7)
                          (first choices)
                          (second choices)))
    "data-type" (fn [{:keys [platform version] :as introspection} context choices]
                  (if (platform-specific-behavior/supports-json-datatype? platform version)
                    (rand-nth choices)
                    (rand-nth (rest choices)) ;; json-type is the first choice listed for data-type, so drop that one and pick from the rest if the platform doesn't support JSON
                    )
                  
)}})

;; We can avoid visiting certain rules multiple times when they are in the top level alternation:
;; i.e. ["foo" [:alternation "bar" "baz"]]
;; table-reference is an instance of this problem.
;; table-name resolves fairly directly, but joined-table, much like the mythical Hydra,
;; results in two more table-references:
;;
;; table-reference = table-name|(' (' joined-table ') ');
;; table-name = identifier [' as ' sql-alias];
;; sql-alias = identifier;
;; joined-table = qualified-join;
;; qualified-join = natural-join;
;; natural-join = table-reference ' natural join ' table-reference;
;;
;; Possible options:
;; 1) Require that the BNF to be non-recursive.
;; 2) Create a list of rules used in the generation of a certain rule, and use that to
;;    drive the decision in alternations and repetitions away from having following the cycle.
;;
;; I decided to go with option 2, with the current caveat that the cycle detection only looks one
;; level down the tree for alternations. So
;;   table-reference = table-name|(' (' joined-table ') ');
;; needs to be written as
;;   table-reference = table-name|joined-table;
;; with the parenthesis moved to a lower rule.
;;
(defn tags-to-exclude-due-to-excessive-visits [context]
  (let [max-cycle-depth 3]
    (as-> (:visited context) $
      (map (fn [[k v]] (when (>= v max-cycle-depth) k)) $)
      (remove nil? $)
      (set $))))

(deftest test-tags-to-exclude-due-to-excessive-visits
  (is (= (tags-to-exclude-due-to-excessive-visits {:visited {:foo 4 :bar 1}})
         #{:foo})))

(defn choose-alternation [options context]
  ;; (println "         Excluding options: "
  ;;          (clojure.set/intersection (set options)
  ;;                                    (set (tags-to-exclude-due-to-excessive-visits context)))
  ;;          "context:" context)
  ;;(println (set options) (set (tags-to-exclude-due-to-excessive-visits context)) context)
  (first (rand-set-element 1 (set options) (set (tags-to-exclude-due-to-excessive-visits context)))))

(deftest test-choose-alternation []
  (is (= (choose-alternation ["simple-rule" "cyclic-rule"] {:visited {"cyclic-rule" 100}})
         "simple-rule")))

(defn gen-from-ast
  "gen-from-ast takes a rule from the AST (with the format provided by the ebnf-parser
  and generates a string that is a syntactically valid example of that rule.

  Take as example the BNF from the example above:
  db-fuzzer.sql-spec> (def full-ast (ebnf-parser \"character_string_type = ('character')|('varchar'['a']);\"))
  [:grammar
   [:rule
    \"character_string_type\"
    [:alternation
     [:grouping [:terminal \"character\"]]
     [:grouping
      [:concatenation
       [:terminal \"varchar\"]
       [:optional [:terminal \"a\"]]]]]]]

  db-fuzzer.sql-spec>   (gen-from-ast (first (rest (rest (first (rest full-ast)))))) ;; sorry for the shenanigans, this function takes the ast that's the right-hand-side of each rule, which is a couple of steps away from the root of the grammar. When at the REPL I just copy'n'paste the right-hand-side that I want, and when not at the REPL gen-fns-from-ast does it for me.
    \"character\" ;; if you run this again, you'll get different random results including varchar and varchara.

  This is used by gen-fns-from-ast to loop over the rules in an AST and generate generator functions for each rule. This is a recursive function that will execute terminal tokens as if they are Clojure functions (which they will be, if you've already defined them or if they are defined in the BNF and gen-fns-from-ast has defined them for you).
  "
  ([ast] ;; the 1-arity is mostly for REPL testing use
   (gen-from-ast ast nil))
  ([ast rule-name] ;; the 2-arity used by gen-fns-from-ast. It partially applies the first two arguments when defining the generator functions so that if you're generator is called table-definition you can just call it like (table-definition).
   (gen-from-ast ast rule-name {} {} {}))
  ([ast rule-name context-hooks introspection context] ;; The 5-arity version has the advantage of supporting the extra context discussed in the above section on context hooks.
   ;; rule-name will only have a value when the generator is called at the top level (i.e. a rule is being evaluted, not an ast element such as :concatenate.
   ;;(println "rule-name:" rule-name "context:" context "ast:" ast)
   (let [context-value-hook (get-in context-hooks [:value rule-name])
         ;; If this is the top-level function and the :shared-state-token isn't present, go ahead and grab it -- we'll give it back at the bottom of this function.
         needs-shared-state-token? (nil? (:shared-state-token context))
         context (if needs-shared-state-token?
                   (assoc context :shared-state-token (rent-an-atom/provision-shared-state!))
                   context)
         context (if rule-name
                   (update-in context [:visited rule-name] (fn [v] (inc (or v 0))))
                   context)
         ;; Now begin the process of generating the result
         result
         ;; The first thing we do is check the :value context hook.
         ;; When present, the value hook bypasses normal evaluation and calls the supplied custom code
         (if context-value-hook
           (context-value-hook introspection context)
           ;; If no value hook was provided, proceed along normal evaluation via the provided AST
           (let [mutate-context-hook (get-in context-hooks [:mutate rule-name])
                 ;; The next hook we look up is the :mutate context hook.
                 ;; This hook modifies the context map. For example, the :mutate context hook for "select" picks a table and a column from that table and puts that in the context map for downstream statements to use.
                 context (if mutate-context-hook
                           (mutate-context-hook introspection context)
                           context)
                 partialed-gen-from-ast (fn [ast] ;; pass in everything but the ast. This is to avoid repetition in the below code.
                                          (gen-from-ast ast nil context-hooks introspection context))]
;;             (println "new context: " context rule-name)
             (if (string? ast)
               (do ;; if we'rein here, ast is a single string that is going to dispatch to another grammar rule
                 (let [fn-symbol (symbol "db-fuzzer.sql-spec" ast)] ;; TODO un-hard-code namespace
                   ;;(println "    fn-symbol:" fn-symbol (find-var fn-symbol))
                   (str (eval
                         (if (not (fn-has-known-arity (var-get (find-var fn-symbol)) 3))
                           (list fn-symbol) ;; if we know if doesn't have 3-arity, it's probably a handwritten generator that doesn't implement the 3-arity, so call the 0-arity.
                           (list fn-symbol context-hooks introspection context) ;; otherwise, keep all our context info by using the 3-arity version!
                           )))))
               (case (first ast) ;; in this block the AST is the right-hand side of a rule that we'll evaluate by normal means. It will look something like [:alternation [:terminal :a] [:terminal :b]].
                 :terminal (second ast)
                 :alternation (let [alternation-hook (get-in context-hooks [:alternation rule-name])
                                    choice (if alternation-hook ;; Use the alternation hook if one is provided
                                             (alternation-hook introspection context (rest ast))
                                             (choose-alternation (rest ast) context)
                                             ;;(rand-nth (rest ast))
                                             )] ;; otherwise just choose an option randomly
                                (partialed-gen-from-ast choice))
                 :grouping (apply str (map ;; (fn [ast]
                                       ;;   (gen-from-ast ast nil context-hooks introspection context))
                                       partialed-gen-from-ast
                                           (rest ast)))
                 :concatenation (apply str (map partialed-gen-from-ast (rest ast)))
                 :repetition (apply str (take (rand-int 6)
                                              (repeatedly #(partialed-gen-from-ast (second ast)))))
                 :optional (if (= 1 (rand-int 2))
                             (partialed-gen-from-ast (second ast))
                             "")
                 "Errorr!!!" ;; TODO throw an exception instead
                 ))))]
     (when needs-shared-state-token? ;; if we grabbed some shared state, we need to put it back now that we're done generating our result
       (rent-an-atom/unprovision-shared-state! (:shared-state-token context)))
     result)))

(defn gen-fns-from-ast
  "This takes in a full AST (such as one generated by the ebnf-parser)
  and plobs a generator function into the namespace for each rule in the AST."
  [ast]
  (assert (= :grammar (first ast)))
  (let [rules (rest ast)]
    (doall (map (fn [[_ rule-fn-name rule-ast]]
                  ;; This next line creates a callable function in the current namespace with whatever is in rule-fn-name as its name
                  (intern *ns* (symbol rule-fn-name)
                          (with-meta 
                            (partial gen-from-ast rule-ast rule-fn-name)
                            {:arglists (list ["context-hooks" "introspection" "context"])} ;; we need to supply the :arglists meta-data since functions defined without defn won't have it unless we supply it, and we need to know the arity in gen-from-ast to determine which arity of the function to call. Since these support the 3-arity for passing in context, we need to let gen-from-ast know that so that it keeps passing the context down.
                            ))
                  )
                rules))))

(defn generate-fns []
  (gen-fns-from-ast (lift-alternations-in-grammar (insta/parse ebnf-parser search-condition-bnf)))
  (gen-fns-from-ast (insta/parse ebnf-parser create-table-bnf))
  (gen-fns-from-ast (insta/parse ebnf-parser sql-module-bnf))
  (gen-fns-from-ast (lift-alternations-in-grammar (insta/parse ebnf-parser datatype-bnf)))
  (gen-fns-from-ast (insta/parse ebnf-parser select-query-bnf)))

(defonce _generate-fns
  (generate-fns))

;; A couple of shims to pass in introspection and context hooks from the test-runner driver
(defn create-statement-shim [introspection]
  [(str (table-definition context-hooks introspection {:skip-table-lookup true}) ";")])

(defn select-statement-shim [introspection]
  [(str (select context-hooks introspection {}) ";")])

(defn select-statement-munged-shim [introspection]
  [(str
    ;;(whitespace-swap (select context-hooks introspection {:munge 3})) ;; MySQL threw parser errors on every query that had a unicode whitespace.
    (select context-hooks introspection {:munge 3})
    ";")])

;; TODO this is just for testing
(def db {:dbtype "mysql"
         :dbname "wordpress"
         :user   "test"
         :password "test"})

(defn create-table-helper []
  (let [statement (table-definition)]
    (println statement)
    (jdbc/execute! db [(str statement ";")])))

