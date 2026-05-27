(ns regesta.app
  "CLI and application shell.

   I/O surface only: file readers, output writers, config loading,
   argument parsing. No business logic. Commands delegate to the core and
   to plugins.

   Commands (target V1):
     regesta run        pipeline.edn INPUT -o OUTPUT
     regesta validate   INPUT
     regesta trace      ASSERTION-ID
     regesta report     RUN-DIR
     regesta inspect    INPUT
     regesta apply-repairs INPUT [--dry-run|--auto-safe|--interactive]
     regesta plugins list

   Not yet implemented.")
