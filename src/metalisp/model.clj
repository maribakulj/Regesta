(ns metalisp.model
  "Canonical internal representation (IR).

   Defines the schemas and constructors for Record, Assertion, Value,
   Provenance, Diagnostic, Repair and Fragment. No logic lives here — only
   data shapes and accessors.

   The model knows only the structural vocabulary (`:meta/*`). Documentary
   predicates (title, agent, date, ...) live in `metalisp.plugins.canonical`
   or in format-specific plugins.

   Implementation arrives in Sprint 1.")
