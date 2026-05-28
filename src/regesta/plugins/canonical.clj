(ns regesta.plugins.canonical
  "Canonical documentary vocabulary plugin.

   Declares the `:canon/*` predicates that format plugins (Dublin Core,
   MARC, CIDOC, …) map to so that cross-source rules and projection can
   operate on a commensurable layer. See ADR 0003.

   Predicates the canonical layer will own:
   - :canon/title
   - :canon/identifier
   - :canon/agent
   - :canon/date
   - :canon/relation
   - :canon/note
   - :canon/digital-object
   - :canon/loss-marker

   Not yet implemented. The plugin map shape is defined by the plugin
   protocol (`regesta.plugins`) and arrives with it.")
