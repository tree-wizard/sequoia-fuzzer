# Mojave Database Query Fuzzer

A SQL query database fuzzer designed for security testing. 

## Features
Mojave creates custom, valid SQL queries and performs targeted mutations on portions of the query.

Best way to see it is to run the tool against a database:

```
% lein run -- -h 192.168.86.57 -u x --password pass --dbtype mysql -d myDB -n 5 -g :complete-select


{:select [:*], :from [:myTable], :join [[:myTable :a1] [:= :a1.balance :myTable.company]], :left-join [], :right-join [], :where [:or [:= :myTable.userid 143029]]} 
{:select [:*], :from [:myTable], :join [[:myTable :a1] [:= :a1.date :myTable.company]], :left-join [[:myTable :a2] [:= :a2.balance :myTable.company]], :right-join []}
{:select [:*], :from [:myTable], :join [], :left-join [[:myTable :a1] [:= :a1.id :myTable.userid]], :right-join [], :where [:and [:= :myTable.username -3]]}
{:select [:*], :from [:myTable], :join [], :left-join [[:myTable :a1] [:= :a1.balance :myTable.date]], :right-join [], :where [:and [:= :myTable.date 121]]}
{:select [:*], :from [:myTable], :join [], :left-join [[:myTable :a1] [:= :a1.username :myTable.userid]], :right-join [], :where [:or [:= :myTable.company -350584]]}

All queries ran successfully. No issues found.
```

We pull table info and create valid queries which are executed against the test DB. A combination of standard options has been found to be very effective in findings vulnerabilities in proprietary databases.

### EBNF Grammar:

A powerful features of this tool is the ability to create custom grammars which trigger specific functionality inside sql queries.

Below is an example select query grammar.

```
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
```

If you would like to target specific functionality or trigger certain elements write a custom grammar and paste into the sql-spec.cljc file.

### Mutations:

Our previous examples simply create new unique queries but a primary goal of ours was to combine the query generation with tried and tested mutation fuzzing techniques of tools like AFL and Libfuzzer. In this way Mojave is able to combine the features of two types of fuzzers for more complete coverage.

For example the :ast-select-munged option utilizes character fuzzing as seen here:

```
% lein run -- -h 192.168.86.57 -u x --password pass --dbtype mysql -d myDB -n 5 -g :ast-select-munged

[select  distinct  *  from mҮTable as a1;] (conn=4566) Table 'myDB.mҮTable' doesn't exist]
[select a1.useRid from  (myTable as a1 natural join myTable as a2) ;] nil]
[select a1٠id, a1.iᎠ, Å1.id, a1.id, a1.id, a1.id from myTable as a1;] (conn=4568) Unknown column 'a1٠id' in 'field list']
[select  *  from  (myTable as a1 natural join myTable as a2) ;] nil]
[select a1.dＡte, a1.däte, a1.dΑte from  (myTable as a1 right outer  join myTable as a2 on a1.userid=a2.id) ;] (conn=4570) Unknown column 'a1.dＡte' in 'field list']
All queries ran successfully. No issues found.
```

Note the mutaions on the query elements.

To further expand the fuzzing capabilities the team is designed the mutations to be modular and are currently integrating fuzzing with radamsa[^https://gitlab.com/akihe/radamsa]. This allows us to fuzz individual sql elements.

This functionality is in development but examples can be seen below from the REPL:


```
% (mutation-fuzz db db-intro)

{:select [:*], :from [:myTa$'$`$&$&$&%d'xcalc!!$&+inf\x0a$+$+$+'xcalc$1!!myTa$'$`$&$&$&%d'xcalc!!$&+inf\x0a$+$+$+'xcalc$1!!myTa$'$`$&$&$&%d'xcalc!!$&+inf\x0a$+$+$+'xcalc$1!!myTa$'$`$&$&$&%d'xcalc!!$&+inf\x0a$+$+$+'xcalc$1!!myTa$'$`$&$&$&%d'xcalc!!$&+inf\x0a$+$+$+'xcalc$1!!myTable], :join [:myTable], :left-join [], :right-join [[:myTable :a2] [:= :a2.confirmedemail :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]]}

{:select [:myTaN\x170141183460469231731687303747483648687303715884105728a"xcalc$'%n$1%pyTable], :from [:myle], :join [[:93k6P1qVx57H :a1] [:= :a1.id :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]], :left-join [], :right-join [[:myTable :a2] [:= :a2.confirmedemail :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]]}

{:select [:myTabl$!!\x0d%s$!!!xcalc%n%#x\x0d&#000;my\r"xcalc%#x\nNaN\x0687303711425657503447303715884105728a"xcalc$&%d%smx0687303715884105728a"xcalc$'%n$1%pyTable], :from [:myle], :join [[:93k6P1qVx57H :a1] [:= :a1.id :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]], :left-join [], :right-join [[:myTable :a2] [:= :a2.confirmedemail :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]]}

{:select [:mlTaaTablTammyTablea�ble], :from [:myle], :join [[:93k6P1qVx57H :a1] [:= :a1.id :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]], :left-join [], :right-join [[:myTable :a2] [:= :a2.confirmedemail :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]]}

{:select [:myTa�ble], :from [:myle], :join [[:93k6P1qVx57H :a1] [:= :a1.id :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]], :left-join [], :right-join [[:myTable :a2] [:= :a2.confirmedemail :g2wEwPmtphgXHfWoyO4hT2N61Pq.id]]}

```

### Code walkthrough
  **./src/clj/db_fuzzer/core.clj** contains the test runner engine and CLI-related functionality.
  
  **./src/clj/db_fuzzer/introspect.clj** contains code for reading in metadata (such as tables and columns) from the target database for use in query construction.
  
  **./src/cljc/db_fuzzer/generate.cljc** contains the configuration map of available generators (used in core.clj) and defines a couple of hand-written query generator functions.
  
  **./src/cljc/db_fuzzer/sql_spec.cljc** contains SQL BNF and code that parses that BNF and turns each rule into a generator function.
  
  **./src/mutations.cljc** - a series of string and integer mutators. This allows us to mutate strings and repalce numbers with known bad values such as:
  
  
  **./src/query-mutator.cljc** - contains the mutation-fuzz function which is used to integrate diffent fuzzing engines.



### Environment Setup

From our testing experience the most effective method for finding bugs is running a debug version of production database in a virtual machine. MariaDB has excellent and easy to follow instructions:

https://mariadb.com/kb/en/compile-and-using-mariadb-with-addresssanitizer-asan/


If you do not have a database of test data you can generate tables from the following site:

http://www.generatedata.com/



## Development Mode

### Start Cider from Emacs:

```
Open ./src/clj/db_fuzzer/core.clj
M-x cider-jack-in
C-c C-k to load the current buffer into the REPL
```

### Run application:
To print the help:
```
lein run -- --help
```

Example run agains the "wordpress" database with user test:
```
lein run -t mysql -d wordpress --user test -p test -n 100 -g ast-select
```

## Production Mode
```
lein clean && lein uberjar
# I've provided example arguments
java -jar ./target/db-fuzzer-0.1.0-SNAPSHOT-standalone.jar -t mysql -d wordpress --user test -p test -n 100 -g ast-select
```

The advantage of the uberjar is that it's configured to ahead-of-time (AOT) compile all the code,
which means that the SQL BNF will get parsed and the generator functions will be generated only once at compile time.
This should give a speed-up compared with using lein run.
