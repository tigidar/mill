# TICKET-008: Worker resolveClasspath implementation

**Stage:** 2 — resolveClasspath
**Depends on:** 006, 007

## Problem

The worker's `resolveClasspath` method is the workhorse — it covers 90 of the 97 call
sites. It must accept the serializable API types, use `MavenResolverEngine` to resolve
dependencies, and return file paths to downloaded artifacts.

## What needs to be done

1. Implement `MavenResolverWorkerImpl.resolveClasspath` in `maven-resolver-worker`:
   - Convert `DependencyCoord` → Maven Resolver `Dependency` objects
   - Convert `RepoCoord` → Maven Resolver `RemoteRepository` objects
   - Convert `BomCoord` → appropriate Maven Resolver BOM handling
   - Apply `forceVersions` as version constraints/overrides
   - Handle `sources` flag (resolve source JARs when true)
   - Handle exclusions from `DependencyCoord.exclusions`
   - Call `MavenResolverEngine.resolve()` with the converted objects
   - Extract file paths from `DependencyResult`
   - Return `Seq[String]` of absolute file paths

2. Implement `MavenResolverWorkerImpl.initialize`:
   - Accept `SessionConfig`
   - Use `SettingsXmlReader` to load settings
   - Create `MavenResolverEngine` session with security hardening
   - Store session for reuse across resolutions

3. Implement `MavenResolverWorkerImpl.close`:
   - Close the engine and release resources

## Verification

- [ ] Unit test: resolve `org.apache.commons:commons-lang3:3.14.0` → returns path to JAR
- [ ] Unit test: resolve with `sources=true` → returns path to sources JAR
- [ ] Unit test: resolve with exclusions → excluded transitive dep is not in result
- [ ] Unit test: resolve with `forceVersions` → specified version is used instead of resolved
- [ ] Unit test: resolve multiple deps → all JARs returned
- [ ] Unit test: resolve non-existent artifact → clear error message with `[maven-resolver]` prefix
- [ ] Unit test: `initialize` + `resolveClasspath` + `close` lifecycle works
- [ ] `./mill libs.javalib.maven-resolver-worker.compile` passes
- [ ] `./mill libs.javalib.maven-resolver-worker.test` passes

## Reference

- Design: Section 3.3 (resolveClasspath API), Section 5.2 (Maven path)
- Error format: Design Section 13, Decision 4 — use `[maven-resolver]` prefix
