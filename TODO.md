# Phase 1: Maven Resolver Backend

## Stage 1 — Configuration plumbing + skeleton

- [x] [001 — ResolverType enum + ConfigConstants + parsing](tickets/001-resolver-type-enum.md) `done`
- [x] [002 — Launcher config reading + daemon propagation](tickets/002-launcher-config-reading.md) `done`
- [x] [003 — Cache invalidation for resolver backend switch](tickets/003-cache-invalidation.md) `done`
- [x] [004 — Worker API trait + serializable data types](tickets/004-worker-api-types.md) `done`
- [x] [005 — maven-resolver-core + maven-resolver-worker module definitions](tickets/005-module-definitions.md) `done`

## Stage 2 — resolveClasspath (90 call sites)

- [x] [006 — MavenResolverEngine — session creation + security hardening](tickets/006-maven-resolver-engine.md) `done`
- [x] [007 — SettingsXmlReader — settings.xml parsing + encrypted passwords](tickets/007-settings-xml-reader.md) `done`
- [x] [008 — Worker resolveClasspath implementation](tickets/008-worker-resolve-classpath.md) `done`
- [x] [009 — Worker loading infrastructure + CoursierModule mixin](tickets/009-worker-loading-infrastructure.md) `done`
- [x] [010 — Backend switch in Jvm.resolveDependencies + worker threading](tickets/010-backend-switch-resolve-dependencies.md) `done`

## Stage 3 — resolveArtifacts (4 call sites)

- [x] [011 — resolveArtifacts + CoursierTypeAdapter + artifact provenance](tickets/011-resolve-artifacts.md) `done`

## Stage 4 — resolveMetadata (3+1 call sites)

- [x] [012 — resolveMetadata + Resolution construction adapter](tickets/012-resolve-metadata.md) `done`

## Stage 5 — Launcher + integration tests

- [x] [013 — Launcher Maven Resolver path for daemon resolution + JVM guard](tickets/013-launcher-maven-resolver.md) `done`
- [x] [014 — Integration tests + CI job for Maven backend](tickets/014-integration-tests-ci.md) `done`
