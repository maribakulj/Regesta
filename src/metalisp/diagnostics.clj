(ns metalisp.diagnostics
  "Diagnostics API.

   Structured diagnostics, severities (`:error`, `:warning`, `:info`),
   grouping by subject and code, human-readable reporting.

   Diagnostics are first-class IR citizens: they are not exceptions. A
   validation rule produces a Diagnostic attached to its subject; the
   pipeline never throws to signal a data problem.

   Implementation arrives in Sprint 4.")
