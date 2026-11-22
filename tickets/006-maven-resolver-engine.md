# TICKET-006: MavenResolverEngine — session creation + security hardening

**Stage:** 2 — resolveClasspath
**Depends on:** 005

## Problem

The core Maven Resolver session must be created with security hardening applied:
configurable checksum policy, SHA-256 preferred, TLS 1.2+ enforcement, offline mode,
and update policies. The session is created once and reused for all resolutions within
a build session.

This lives in `maven-resolver-core` so both the worker and launcher can use it.

## What needs to be done

1. Create `MavenResolverEngine` in `libs/javalib/maven-resolver-core/src/`
2. The engine must:
   - Create a `RepositorySystem` via `RepositorySystemSupplier`
   - Create a `DefaultRepositorySystemSession` with security hardening from `SessionConfig`:
     - Local repository path
     - Checksum policy (fail/warn/ignore)
     - Preferred checksum algorithms (SHA-256, SHA-1)
     - TLS protocol enforcement (TLSv1.2, TLSv1.3)
     - Offline mode
     - Update policy (always/daily/interval/never)
   - Apply mirrors, authentication, and proxies from parsed `Settings`
   - Make session read-only after configuration
   - Expose a `resolve()` method that executes dependency resolution
   - Implement `AutoCloseable` to close the `RepositorySystem`

Note: `DefaultRepositorySystemSession()` constructor is deprecated in 2.x but needed
due to classloader issues with `system.createSessionBuilder()` — same issue as existing
`maven-worker`. Use `@nowarn`.

## Verification

- [ ] Unit test: create engine with default `SessionConfig`, verify session is created
- [ ] Unit test: checksum policy from config is applied to session
- [ ] Unit test: TLS protocols from config are set as session config property
- [ ] Unit test: offline=true produces an offline session
- [ ] Unit test: session is read-only after creation (mutation throws)
- [ ] Unit test: resolve a well-known artifact (e.g., `commons-lang3`) against Maven Central
      using a local repository temp directory — verify JAR is downloaded
- [ ] `./mill libs.javalib.maven-resolver-core.compile` passes

## Reference

- Design: Section 4.1
- Security settings: design Section 4.1 lines on session hardening
- Deprecation note: design Section 4.1 comment about `@nowarn`
