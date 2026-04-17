(ns metalisp.runtime
  "Rule execution engine.

   Applies a compiled rule set to a record, collects productions
   (assertions, diagnostics, repair proposals, projection intents), and
   merges them back into the record while tracking provenance.

   Phase-aware: the runtime executes only rules whose `:phase` matches the
   current pass. No cross-phase action at a distance.

   Implementation arrives in Sprint 3.")
