(ns metalisp.rules
  "Declarative rule DSL.

   A rule is an EDN map with `:id`, `:phase`, `:match` and `:produce` keys.
   This namespace defines the rule schema, the rule compiler (data → function),
   and the curated predicate stdlib available in `:match` clauses.

   Rules are first-class data: inspectable, serializable, composable. The core
   never accepts arbitrary Clojure functions inside a rule; logic must pass
   through the compiler.

   Implementation arrives in Sprint 2.")
