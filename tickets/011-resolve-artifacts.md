# TICKET-011: resolveArtifacts + CoursierTypeAdapter + artifact provenance

**Stage:** 3 — resolveArtifacts
**Depends on:** 010

## Problem

`Jvm.fetchArtifacts` and `Jvm.getArtifacts` return Coursier-specific types
(`coursier.Fetch.Result` containing `(Dependency, Publication, Artifact, File)` tuples).
4 call sites use these methods (QuarkusModule 3x, NativeImageModule 1x).

The Maven backend must produce equivalent Coursier types from Maven Resolver results.
Additionally, `ResolvedArtifact.repositoryUrl` must be populated from
`ArtifactResult.getRepository()` — this is the integration point for Phase 1.b Sigstore
verification.

## What needs to be done

1. Implement `resolveArtifacts` in the worker:
   - Resolve dependencies and return `Seq[ResolvedArtifact]` with all fields populated
   - `repositoryUrl` comes from `ArtifactResult.getRepository().getUrl()`
   - Cache results for `lastResolvedArtifacts()`

2. Implement `lastResolvedArtifacts()` in the worker:
   - Return the cached `Seq[ResolvedArtifact]` from the most recent resolution

3. Create `CoursierTypeAdapter` (in `libs/util/java11/`):
   - Convert `ResolvedArtifact` → `(coursier.Dependency, Publication, Artifact, File)` tuple
   - Construct a `coursier.Fetch.Result` from the converted tuples

4. Add backend switch to `Jvm.fetchArtifacts` and `Jvm.getArtifacts`:
   - Extract Coursier path into private method
   - Add Maven path using worker + adapter

5. Add `resolvedArtifactMetadata` task to `CoursierModule`:
   - Returns `Seq[ResolvedArtifact]` — from worker cache when Maven, from Coursier
     resolution data when Coursier

## Verification

- [ ] Unit test: `CoursierTypeAdapter` round-trips a `ResolvedArtifact` to Coursier types
      with correct organization, name, version, classifier, and file path
- [ ] Unit test: `resolveArtifacts` populates `repositoryUrl` (not empty)
- [ ] Unit test: `lastResolvedArtifacts()` returns results from most recent resolution
- [ ] With Maven backend: QuarkusModule tests pass (accesses org/name/version/file)
- [ ] With Maven backend: NativeImageModule tests pass
- [ ] `resolvedArtifactMetadata` task returns populated data with Maven backend
- [ ] `./mill __.compile` passes with default backend
- [ ] All existing tests pass (Coursier path unchanged)

## Reference

- Design: Section 5.3 (fetchArtifacts Maven path), Section 6.4 (artifact provenance)
- Caller analysis: Design Section 5.3 table (what callers actually access)
- Key files:
  - `libs/util/java11/src/mill/util/Jvm.scala`
  - New: `libs/util/java11/src/mill/util/CoursierTypeAdapter.scala`
