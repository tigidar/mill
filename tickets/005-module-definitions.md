# TICKET-005: maven-resolver-core + maven-resolver-worker module definitions

**Stage:** 1 — Configuration plumbing
**Depends on:** 004

## Problem

Maven Resolver JARs (Apache HttpClient, SLF4J, Plexus, etc.) conflict with Mill's
classpath. The worker pattern (isolated classloader) solves this for the daemon. But the
launcher also needs the same engine logic and runs before the daemon exists.

A shared `maven-resolver-core` module contains the pure Maven Resolver engine logic
(no Mill dependencies). Both the worker and the launcher depend on it.

## What needs to be done

1. Create `libs/javalib/maven-resolver-core/` module:
   - `package.mill` with Maven Resolver 2.0.13 dependencies (resolver-api, resolver-util,
     resolver-connector-basic, resolver-supplier, resolver-transport-http,
     resolver-transport-file, maven-settings, maven-settings-builder,
     plexus-sec-dispatcher:4.1.0)
   - Empty source directory structure:
     `src/mill/javalib/maven/resolver/core/`

2. Create `libs/javalib/maven-resolver-worker/` module:
   - `package.mill` depending on `maven-resolver-core` and `build.libs.javalib`
   - Empty `MavenResolverWorkerImpl.scala` skeleton that implements
     `MavenResolverSupport.Api` with stub methods (throw `NotImplementedError`)
   - Source directory: `src/mill/javalib/maven/resolver/worker/impl/`

3. Register both modules in `libs/javalib/package.mill`

## Verification

- [ ] `./mill libs.javalib.maven-resolver-core.compile` succeeds
- [ ] `./mill libs.javalib.maven-resolver-worker.compile` succeeds
- [ ] Maven Resolver 2.0.13 dependencies resolve correctly (all aligned to same version)
- [ ] `plexus-sec-dispatcher:4.1.0` resolves
- [ ] Worker module can reference `MavenResolverSupport.Api` from ticket 004
- [ ] `./mill __.compile` passes — no classpath conflicts
- [ ] `./mill visualizeMillModules` shows both new modules in the dependency graph

## Reference

- Design: Section 3.1, 3.2
- Version alignment: Maven Resolver 2.0.13, Maven 3.9.9, plexus-sec-dispatcher 4.1.0
- Pattern to follow: existing `maven-worker` module for publishing
