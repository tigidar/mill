# TICKET-012: resolveMetadata + Resolution construction adapter

**Stage:** 4 — resolveMetadata
**Depends on:** 010

## Problem

`Jvm.resolveDependenciesMetadataSafe` returns a `coursier.core.Resolution` — the most
complex type to construct from Maven Resolver results. 3+1 callers access deep Coursier
internals: `finalDependenciesCache`, `projectCache` with `overrides` and `licenseInfo`,
`orderedDependencies`, and `dependencies`.

## What needs to be done

1. Implement `resolveMetadata` in the worker:
   - Resolve dependencies and extract the full dependency graph
   - Build `transitiveDeps`: root dep key → all transitive dep coords
   - Build `projectCache`: `(groupId:artifactId:version)` → `ProjectInfo` with BOM
     overrides, licenses, and parent POM info
   - Build `orderedDeps`: all resolved deps in resolution order
   - Return `ResolutionMetadata`

2. Add Resolution construction to `CoursierTypeAdapter`:
   - Convert `ResolutionMetadata.transitiveDeps` → `Resolution.finalDependenciesCache`
   - Convert `ResolutionMetadata.projectCache` → `Resolution.projectCache` with
     `DependencyManagement.Key/Values` for overrides and `Info.License` for licenses
   - Convert `ResolutionMetadata.orderedDeps` → `Resolution.orderedDependencies`
   - Set `Resolution.dependencies` from `orderedDeps.toSet`
   - Construct `Resolution` with `.withFinalDependenciesCache(...)` etc.

3. Add backend switch to `Jvm.resolveDependenciesMetadataSafe`:
   - Extract Coursier path into private method
   - Add Maven path using worker + adapter

## Verification

- [ ] Unit test: `resolveMetadata` for a dep with transitives returns correct
      `transitiveDeps` mapping
- [ ] Unit test: `projectCache` contains BOM overrides when resolving a BOM-managed dep
- [ ] Unit test: `projectCache` contains license info extracted from POM
- [ ] Unit test: constructed `Resolution.finalDependenciesCache` returns correct deps for
      a root dependency
- [ ] Unit test: constructed `Resolution.orderedDependencies` contains all resolved deps
- [ ] With Maven backend: `publishLocal` generates correct POM/ivy.xml (uses
      `finalDependenciesCache` and `projectCache.overrides`)
- [ ] With Maven backend: BSP module lists dependencies correctly (uses `orderedDependencies`)
- [ ] With Maven backend: `CycloneDXJavaModule` SBOM includes license info
- [ ] `./mill __.compile` passes with default backend
- [ ] All existing tests pass

## Reference

- Design: Section 5.4 (Resolution construction), Section 8.4 (type mapping)
- Caller analysis: Design Section 5.4 table (PublishModule, BspJavaModule,
  AndroidR8AppModule, CycloneDXJavaModule)
- Risk: only fields callers actually read are populated; others are defaults
