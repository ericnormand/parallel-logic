# Parallel Logic Programming System

## Project Overview

This project implements a parallel logic programming system in Clojure, inspired by miniKanren and similar relational programming languages. The system uses virtual threads and core.async channels to enable concurrent goal solving, allowing multiple solutions to be computed in parallel. It provides unification-based constraint solving with support for logical variables, disjunction (OR), and conjunction (AND) operations.

## Key Features

- **Parallel Goal Execution**: Uses Java virtual threads (Project Loom) for lightweight concurrent processing
- **Unification Algorithm**: Delta-based unification with support for variables, constants, and collections
- **Logical Operations**: Disjunction and conjunction with proper backtracking and solution merging
- **Stream-based Results**: Solutions are delivered via core.async channels for non-blocking consumption

## Architecture

The system is built around several core concepts:

1. **Variables**: Represented as namespaced symbols in the `v` namespace (e.g., `v/x`)
2. **Substitutions**: Maps from variables to values or other variables
3. **Goals**: Functions that take a substitution and return a channel of solution deltas
4. **Deltas**: Partial substitutions representing individual constraint solutions

## Key Files

### `/src/parallel_logic/core.clj`
The main implementation containing all core logic programming primitives:

- **Variable System**: `v` macro and `var?` predicate for creating and identifying logic variables
- **Unification Engine**: `delta-unify` for constraint solving and `unify` for merging solutions  
- **Goal Constructors**: `==` for equality constraints, `disjoin` for OR, `conjoin` for AND
- **Substitution Utilities**: `walk` for variable resolution in substitutions

### `/test/parallel_logic/core_test.clj`
Comprehensive test suite covering all functionality:

- Unit tests for unification algorithms with edge cases
- Goal execution tests with parallel behavior validation
- Helper utilities like `channel->set` for testing async results
- Property tests for commutativity and correctness

### `/deps.edn`
Project configuration with minimal dependencies:

- **Core Dependencies**: Clojure 1.12.1, core.async 1.8.741
- **Development Tools**: nREPL configuration for REPL-driven development

## Core APIs and Usage Examples

### Creating Variables
```clojure
(require '[parallel-logic.core :refer [v]])

;; Create logic variables
(v x)     ; => v/x
(v name)  ; => v/name
```

### Basic Unification
```clojure
(require '[parallel-logic.core :as logic])

;; Unify a variable with a value
((logic/== (v x) 5) {})  ; Returns channel with solution {v/x 5}

;; Unify two variables
((logic/== (v x) (v y)) {})  ; Returns channel with solution {v/y v/x}
```

### Disjunction (OR Logic)
```clojure
;; x can be either 1 OR 2
(let [goal (logic/disjoin (logic/== (v x) 1)
                         (logic/== (v x) 2))]
  (channel->set (goal {})))
;; => #{{v/x 1} {v/x 2}}
```

### Conjunction (AND Logic)  
```clojure
;; x must be 1 AND y must be 2
(let [goal (logic/conjoin (logic/== (v x) 1)
                         (logic/== (v y) 2))]
  (channel->set (goal {})))
;; => #{{v/x 1, v/y 2}}
```

### Collection Unification
```clojure
;; Unify vectors element-wise
((logic/== [(v x) (v y)] [1 2]) {})
;; Returns channel with solution {v/x 1, v/y 2}
```

## Implementation Patterns

### Goal Protocol
Goals are functions with signature: `substitution -> channel<delta>`
- Take a current substitution state
- Return a core.async channel that will emit solution deltas
- Each delta represents a partial solution that extends the substitution

### Parallel Execution
- `disjoin`: Runs goals concurrently, merging all solutions from all branches
- `conjoin`: Runs goals concurrently, then unifies solutions pairwise when both complete
- Uses `alts!!` for non-blocking channel operations and fair scheduling

### Variable Ordering
- Variables are consistently ordered by string comparison for deterministic results
- When unifying two variables, the lexicographically smaller one is preserved

## Development Workflow

### Running Tests
```bash
clojure -X:test  # or use your preferred test runner
```

### REPL Development
```bash
# Start nREPL server
clojure -M:nrepl-mcp

# Connect with your editor to localhost:7888
```

### Testing Channel Results
Use the provided `channel->set` utility function to convert async channel results to sets for easy testing:

```clojure
(defn channel->set [ch]
  (loop [results #{}]
    (let [val (<!! ch)]
      (if (nil? val)
        (when (seq results) results)
        (recur (conj results val))))))
```

## Extension Points

### Custom Goals
Implement new goal types by creating functions that:
1. Take a substitution map
2. Return a core.async channel  
3. Emit zero or more solution deltas
4. Close the channel when complete

### Advanced Unification
Extend `delta-unify` to handle:
- Custom data structures
- Occurs check for infinite structures  
- Constraint propagation

### Performance Optimization
- Add indexing for large variable spaces
- Implement tabling/memoization for recursive relations
- Add cut operators for pruning search spaces

### Language Extensions  
- Add arithmetic constraints (CLP(FD))
- Implement assert/retract for dynamic facts
- Add pattern matching beyond unification

## Dependencies

### Runtime Dependencies
- **org.clojure/clojure 1.12.1**: Core language runtime with virtual thread support
- **org.clojure/core.async 1.8.741**: Channel-based concurrency primitives

### Development Dependencies  
- **nrepl/nrepl 1.3.1**: Network REPL for interactive development

## Performance Characteristics

- **Concurrency**: Leverages virtual threads for massive parallelism with minimal overhead
- **Memory**: Each goal creates lightweight channels; solutions are streamed to avoid memory buildup  
- **Fairness**: Uses `alts!!` to ensure fair scheduling between competing goals
- **Backtracking**: Implicit through channel-based solution enumeration

This system is designed for educational purposes and experimentation with parallel logic programming concepts. It demonstrates how traditional miniKanren-style relational programming can be enhanced with modern JVM concurrency primitives.
