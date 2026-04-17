(ns metalisp.app
  "CLI and application shell.

   I/O surface only: file readers, output writers, config loading,
   argument parsing. No business logic. Commands delegate to the core and
   to plugins.

   Commands (target V1):
     metalisp run        pipeline.edn INPUT -o OUTPUT
     metalisp validate   INPUT
     metalisp trace      ASSERTION-ID
     metalisp report     RUN-DIR
     metalisp inspect    INPUT
     metalisp apply-repairs INPUT [--dry-run|--auto-safe|--interactive]
     metalisp plugins list

   Implementation arrives in Sprint 10.")
