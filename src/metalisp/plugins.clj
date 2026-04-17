(ns metalisp.plugins
  "Plugin protocol and registry.

   A plugin is a bundle of:
   - an importer (external format → canonical IR)
   - an exporter (canonical IR → external format)
   - optional rule sets
   - a declared mapping to the canonical vocabulary (`metalisp.plugins.canonical`)

   Plugins connect the outside world to the core. They never replace the
   core and never reach into its internals: they interact only through the
   rule DSL and the model API.

   Implementation arrives in Sprint 5.")
