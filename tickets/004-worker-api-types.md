# TICKET-004: Worker API trait + serializable data types

**Stage:** 1 — Configuration plumbing
**Depends on:** —

## Problem

The Maven Resolver worker runs in an isolated classloader. Communication between Mill's
main classpath and the worker must use types visible to both sides — plain Scala case
classes with `String`/`Seq` fields, no Coursier or Maven Resolver types.

This ticket defines the API contract. The actual implementation comes in later tickets.

## What needs to be done

1. Create `libs/javalib/src/mill/javalib/internal/MavenResolverSupport.scala`
2. Define the `MavenResolverSupport` object containing:
   - `trait Api extends AutoCloseable` with methods:
     - `initialize(config: SessionConfig): Unit`
     - `resolveClasspath(deps, repositories, sources, forceVersions, bomDeps): Seq[String]`
     - `resolveArtifacts(deps, repositories, sources, forceVersions, bomDeps): Seq[ResolvedArtifact]`
     - `resolveMetadata(deps, repositories, forceVersions, bomDeps): ResolutionMetadata`
     - `lastResolvedArtifacts(): Seq[ResolvedArtifact]`
   - `case class SessionConfig(...)` — local repo path, settings.xml paths, checksum policy,
     checksum algorithms, TLS protocols, offline, update policy, snapshots enabled
   - `case class DependencyCoord(...)` — groupId, artifactId, version, configuration,
     exclusions, classifier, type
   - `case class RepoCoord(...)` — id, url, type
   - `case class BomCoord(...)` — groupId, artifactId, version
   - `case class ResolvedArtifact(...)` — groupId, artifactId, version, classifier, path,
     repositoryUrl
   - `case class ResolutionMetadata(...)` — transitiveDeps, projectCache, orderedDeps
   - `case class ResolvedDep(...)` — groupId, artifactId, version, configuration
   - `case class ProjectInfo(...)` — overrides, licenses, parent

## Verification

- [ ] All types compile and are plain Scala case classes (no external dependencies)
- [ ] `Api` trait methods match the three resolution entry points in `Jvm.scala`
- [ ] `SessionConfig` has all security-relevant fields from the design
- [ ] `ResolvedArtifact` includes `repositoryUrl` field (needed for Phase 1.b Sigstore)
- [ ] `./mill __.compile` passes
- [ ] No existing module depends on Maven Resolver or Coursier types through these definitions

## Reference

- Design: Section 3.3 (full type definitions)
- Pattern to follow: existing `MavenWorkerSupport` for publishing
