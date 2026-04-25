# Security policy

## Scope

Regesta is a library and CLI that processes user-supplied metadata
records and rule files. The realistic attack surface is:

- A maliciously crafted rule file that exploits the compiler.
- A maliciously crafted source record that exploits an importer.
- A registered plugin that abuses its access to the runtime.

Plugins are loaded by explicit `require` and `register` calls; the
project's trust model assumes you trust the plugins you load (see
ADR 0010). Sandboxing of plugin code is explicitly out of scope for
V1.

## Reporting a vulnerability

**Please do not open a public GitHub issue for a suspected security
vulnerability.**

Instead, report privately by one of these channels:

- GitHub's [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
  feature for this repository (preferred — leaves an audit trail).
- Direct message to a maintainer listed in [CODEOWNERS](./.github/CODEOWNERS).

In your report, include:

- The version (commit SHA, tag, or release).
- A minimal reproduction: input rules, input records, command line.
- The behavior you observed and what you expected instead.
- The impact, as you understand it (information disclosure, denial of
  service, code execution, etc.).

## What to expect

- Acknowledgement within **5 working days**.
- A first assessment (severity estimate, affected versions) within
  **15 working days**.
- A coordinated disclosure timeline agreed in writing — typically 30
  to 90 days depending on severity, complexity, and whether a
  workaround exists.

If a fix lands before the disclosure date, it ships with a security
advisory. Reporters are credited unless they prefer otherwise.

## Supported versions

Until v1.0.0, only the latest tagged release on `main` is supported.
Post-1.0 the supported-versions table will be filled in here.

## Out of scope

These are *not* considered vulnerabilities for the purposes of this
policy:

- Resource exhaustion from a deliberately enormous input. Regesta is a
  batch tool; input sizes are an operational concern.
- A registered plugin's misbehavior. The trust model is documented in
  ADR 0010 — registering a plugin is granting it execution.
- Issues in dependencies. Forward those to the upstream project. We
  accept reports about *our use* of a dependency that exposes its
  vulnerability needlessly (e.g. running it against untrusted input
  when we don't have to).
