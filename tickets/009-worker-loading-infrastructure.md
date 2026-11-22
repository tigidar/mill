# TICKET-009: Worker loading infrastructure + CoursierModule mixin

**Stage:** 2 — resolveClasspath
**Depends on:** 004

## Problem

The `MavenResolverWorkerImpl` runs in an isolated classloader. Mill needs infrastructure
to resolve the worker's classpath, create the classloader, load the implementation class,
and expose the `Api` instance to modules that need it.

This follows the established worker pattern used by `MavenWorkerSupport` for publishing.

## What needs to be done

1. Create `libs/javalib/src/mill/javalib/MavenResolverSupport.scala` (the loading trait):
   - `mavenResolverWorkerClasspath: T[Seq[PathRef]]` — resolves
     `mill-libs-javalib-maven-resolver-worker` via default resolver (always Coursier,
     since this resolves Mill's own internals)
   - `mavenResolverWorkerClassloader: Task.Worker[ClassLoader & AutoCloseable]` — creates
     isolated classloader
   - `mavenResolverWorker: Task.Worker[MavenResolverSupport.Api]` — loads and instantiates
     `MavenResolverWorkerImpl`

2. Mix `MavenResolverSupport` into `CoursierModule`:
   - `trait CoursierModule extends mill.Module with MavenResolverSupport`
   - This makes `mavenResolverWorker` available to all modules that use resolution

## Verification

- [ ] `MavenResolverSupport` trait compiles and follows the same pattern as `MavenWorkerSupport`
- [ ] `CoursierModule` compiles with the new mixin
- [ ] Worker classpath resolves the correct module (`mill-libs-javalib-maven-resolver-worker`)
- [ ] Worker classpath resolution always uses Coursier (not affected by backend switch)
- [ ] `./mill __.compile` passes
- [ ] All existing `CoursierModule` tests pass (mixin adds no behavioral change when
      Maven backend is not active)

## Reference

- Design: Section 6.1, 6.2, 6.3
- Pattern to follow: `MavenWorkerSupport` in `libs/javalib/`
- Key files:
  - `libs/javalib/src/mill/javalib/CoursierModule.scala`
  - Existing `MavenWorkerSupport` (for publishing) as template
