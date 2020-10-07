# Mojave Database Query Fuzzer

A SQL query database fuzzer designed for security testing. 

## Features
Mojave creates custom, valid SQL queries and performs targeted mutations on portions of the query.

Best way to see it is to run the tool against a database:




As you can see we pull table info and create valid quries which are execututed against the test DB. A combination of standard options has been found to be very effecttive in findings vulnerabilities in propreitary databases.


### EBNF Grammar:

A powerful features of this tool is the ability to create custom grammars which hit specific functionality of the sql queries.


This allows you to highly target the sql quuery:


When adding new functionality such as elements the tester will create a new grammar with said funcitonality and runt he queries.


### Mutations:

Our previous examples simply create new unique queries but a primary goal of ours was to combine the query generation with tried and tested mutation fuzzing techniaues of tools like AFL and Libfuzzer. In this way Mojave is able to combine the features of two tyeps of fuzzers for more ccomplete ccoverage.

For example the :ast-select-munged option utilizes charactter fuzzing as seen here:


As you can see we are modify legitame queries.

To furhter expand the fuzzing capabilites the team is designed the mutatoins to be modular and are currently integrating fuzzing with radamsa[^] a. This allows us too fuzz individual sql elements.

This functionality is in development but examples can be seen below from the REPL:




### Code walkthrough
  ./src/clj/db_fuzzer/core.clj contains the test runner engine and CLI-related functionality.
  
  
  ./src/clj/db_fuzzer/introspect.clj contains code for reading in metadata (such as tables and columns) from the target database for use in query construction.
  
  ./src/cljc/db_fuzzer/generate.cljc contains the configuration map of available generators (used in core.clj) and defines a couple of hand-written query generator functions.
  
  ./src/cljc/db_fuzzer/sql_spec.cljc contains SQL BNF and code that parses that BNF and turns each rule into a generator function.
  
  ./src/mutations.clj - a series of string and integer mutators. This allows us to mutate strings and repalce numbers with known bad values such as:
  
  ./src/query-mutator.clj



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
