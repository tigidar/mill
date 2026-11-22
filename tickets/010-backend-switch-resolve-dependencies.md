# TICKET-010: Backend switch in Jvm.resolveDependencies + worker threading

**Stage:** 2 — resolveClasspath
**Depends on:** 008, 009

## Problem

`Jvm.resolveDependencies` is the main entry point for dependency resolution, covering
90 call sites. It needs a backend switch: when `ResolverType.Maven` is active, delegate
to the Maven Resolver worker instead of Coursier.

The worker reference must be threaded from `CoursierModule` through `Lib.scala` to
`Jvm.scala`. The existing Coursier implementation must be extracted into a private method
with zero logic changes.

## What needs to be done

1. Thread the Maven worker reference to `Jvm.scala`:
   - Add `mavenResolverWorker: Option[MavenResolverSupport.Api] = None` to `CoursierConfig`
     (or as a separate parameter — decide during implementation)
   - Thread it through `Lib.resolveDependencies` → `Jvm.resolveDependencies`
   - `CoursierModule.Resolver` passes the worker when backend is `Maven`

2. In `Jvm.resolveDependencies`:
   - Extract existing Coursier code into `private def resolveDependenciesCoursier(...)` —
     pure rename, zero logic change
   - Add `private def resolveDependenciesMaven(...)` that:
     - Converts Coursier `Dependency` → `DependencyCoord`
     - Converts `MavenRepository` → `RepoCoord` (skip Ivy repos with warning)
     - Converts force versions and BOMs
     - Calls `mavenWorker.resolveClasspath(...)`
     - Wraps result paths as `Seq[PathRef]`
   - Add `ResolverType.current match` at the top dispatching to the appropriate method

3. Add Coursier → worker type conversion helpers (`coursierDepToCoord`, `coursierBomToCoord`)

## Verification

- [ ] With `ResolverType.Coursier` (default): behavior is identical to before (pure refactor)
- [ ] With `ResolverType.Maven`: resolution goes through Maven Resolver worker
- [ ] `./mill -Dmill.resolver.backend=maven scalalib.compile` resolves all dependencies
- [ ] Ivy repositories produce a warning and are skipped (not a crash)
- [ ] `mapDependencies` is applied before conversion (works with Maven backend)
- [ ] `./mill __.compile` passes with default backend
- [ ] `./mill core.__.test` passes with default backend
- [ ] Run `example.javalib.basic.1-script` with both backends — both succeed

## Reference

- Design: Section 5.1, 5.2, 5.5, 5.6
- Key files:
  - `libs/util/java11/src/mill/util/Jvm.scala`
  - `libs/util/java11/src/mill/util/CoursierConfig.scala`
  - `libs/javalib/src/mill/javalib/Lib.scala`
  - `libs/javalib/src/mill/javalib/CoursierModule.scala`
