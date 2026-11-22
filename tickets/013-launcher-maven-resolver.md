# TICKET-013: Launcher Maven Resolver path for daemon resolution + JVM guard

**Stage:** 5 — Launcher
**Depends on:** 006, 007

## Problem

The launcher resolves the Mill daemon classpath (`mill-runner-daemon` and transitive deps)
through the same network proxy as user dependencies. The OkHttp hostname verification bug
in Coursier applies here too — a MITM at the proxy could serve a trojaned daemon JAR with
full access to source code, env vars, CI secrets, and credentials.

The launcher must use Maven Resolver when the `maven` backend is active. It runs before
the Mill daemon exists, so the worker pattern is not available — it uses
`maven-resolver-core` directly.

Additionally, JVM download via Coursier's JVM index must be disabled when Maven backend
is active, with a clear error directing users to set JAVA_HOME.

## What needs to be done

1. Add `maven-resolver-core` as a dependency of `runner/launcher/package.mill`

2. Add Maven path to `CoursierClient.resolveMillDaemon()`:
   - Use `MavenResolverEngine` from `maven-resolver-core` directly (no classloader isolation)
   - Use `SettingsXmlReader` to load settings.xml for mirrors, credentials, proxies
   - Apply session hardening (checksum, TLS, SHA-256)
   - Resolve `com.lihaoyi:mill-runner-daemon_3:{millVersion}` and transitive deps
   - Return artifact paths
   - Backend switch via `ResolverType` from ticket 002

3. Add JVM resolution guard to `CoursierClient.resolveJavaHome()`:
   - When `ResolverType.Maven`: check `JAVA_HOME` or `java.home` system property
   - If neither available: fail with clear error message:
     "Maven resolver backend requires a system-installed JVM. Set JAVA_HOME or use
     mill-jvm-version: system"
   - When `ResolverType.Coursier`: existing behavior unchanged

## Verification

- [ ] With `maven` backend: daemon JARs resolve via Maven Resolver (not Coursier/OkHttp)
- [ ] With `maven` backend: settings.xml mirrors/credentials/proxies are applied to
      daemon resolution
- [ ] With `maven` backend + JAVA_HOME set: Mill starts normally
- [ ] With `maven` backend + no JAVA_HOME + no system JVM: clear error message
- [ ] With `coursier` backend: all existing behavior unchanged
- [ ] Launcher compiles with `maven-resolver-core` dependency
- [ ] `./mill __.compile` passes

## Reference

- Design: Section 7.1, 7.2
- Security rationale: Design Section 7.1 (MITM risk for daemon JARs)
- Key files:
  - `runner/launcher/src/mill/launcher/CoursierClient.scala`
  - `runner/launcher/src/mill/launcher/MillProcessLauncher.scala`
  - `runner/launcher/package.mill`
