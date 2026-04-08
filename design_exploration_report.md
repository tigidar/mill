# Maven Resolver Integration: Design Exploration Report

**Date:** 2026-04-08
**Status:** Approach selected, pending detailed design
**Selected approach:** Approach 5 — Configuration-based backend switch with worker isolation

## Motivation

Maven Resolver (formerly Eclipse Aether) needs to be a configurable alternative to
Coursier for artifact resolution in Mill. The primary driver is **compliance**: in
some organisations Maven Resolver is the only whitelisted tool for dependency resolution,
and compliance takes precedence over feature parity.

When Maven Resolver is active, **nothing may depend on Coursier** — the entire resolution
path must be free of Coursier. JVM management becomes `system`-only (no Coursier JVM
downloads), which aligns with the compliance scenario since those organisations also
mandate system-installed JDKs.

## Current State

### Coursier roles in Mill (4 distinct concerns)

1. **Dependency resolution** — resolve `groupId:artifactId:version` to transitive closure of JARs
2. **Artifact fetching** — download JARs/POMs/sources with caching, credentials, mirrors
3. **JVM management** — download/manage JDK distributions (Temurin, GraalVM, etc.)
4. **Publishing metadata** — extract BOM/depManagement info for POM generation

### Existing Maven Resolver usage in Mill

Mill already uses Maven Resolver in two places via the worker pattern:

- `libs/init/maven/` — POM reading for Maven-to-Mill project migration
  (`Resolver.scala`, `Modeler.scala`)
- `libs/javalib/maven-worker/` — artifact publishing to Maven repos
  (`WorkerRemoteM2Publisher.scala`)

### Key Coursier integration points

| File | Purpose |
|------|---------|
| `libs/util/java11/src/mill/util/Jvm.scala` | Core resolution: `resolveDependencies`, `fetchArtifacts`, `resolveDependenciesMetadataSafe`, JVM resolution, cache setup |
| `libs/javalib/src/mill/javalib/CoursierModule.scala` | User-facing module trait with `Resolver` class, repository config, customization hooks |
| `libs/javalib/src/mill/javalib/CoursierConfigModule.scala` | Environment/property-based config (`COURSIER_*` env vars, credentials, mirrors) |
| `libs/util/java11/src/mill/util/CoursierConfig.scala` | Config case class aggregating cache, credentials, mirrors, policies |
| `libs/javalib/src/mill/javalib/Dep.scala` | Mill's `Dep` / `BoundDep` types wrapping `coursier.Dependency` |
| `libs/javalib/src/mill/javalib/Lib.scala` | Dep binding, conversion, resolution entry points |
| `libs/javalib/src/mill/javalib/JavaModule.scala` | Module integration: classpath assembly, `coursierProject`, internal repository |
| `runner/launcher/src/mill/launcher/CoursierClient.scala` | Launcher-level: Mill daemon classpath resolution + JVM resolution |
| `libs/util/java11/src/mill/util/CoursierTickerResolutionLogger.scala` | Download progress reporting |
| `libs/javalib/src/mill/javalib/JavaHomeModule.scala` | Per-module JVM version via Coursier JVM index |

### No existing abstraction

- `CoursierModule.Resolver` is a concrete class (not a trait)
- `Dep` and `BoundDep` wrap `coursier.Dependency` directly
- Coursier types (`Repository`, `Resolution`, `Credentials`, `Mirror`) leak through
  the entire API surface (~15 files, ~20 import sites)

## Approaches Considered

### Approach 1: Full Type Abstraction

Create `mill.resolver.*` package with backend-agnostic types replacing all Coursier
types throughout Mill.

**Rejected.** Touches 15+ files, 1000+ lines. Breaks backward compatibility for anyone
extending `CoursierModule`. Highest risk path for the most critical code path in a build
tool. Potentially a long-term goal after integration proves its value.

### Approach 2: Thin Adapter at Resolution Boundary

Keep Coursier types in Mill's public API. Add adapter in `Jvm.scala` that converts
Coursier types to Maven Resolver types and back.

**Rejected.** Leaky abstraction — Coursier types visible even when Maven is active.
Faking `coursier.Resolution` metadata from Maven Resolver output is brittle. Violates
the constraint that nothing should depend on Coursier when Maven backend is selected.

### Approach 3: Worker-Isolated Resolver with Simple API

Define a serializable API boundary in a small module. Each backend (Coursier, Maven)
implements it in its own classloader via Mill's worker pattern.

**Viable as internal architecture.** Clean isolation, follows Mill's established
patterns (Zinc workers, Maven publish workers), testable independently. Forms the
foundation of the selected approach.

### Approach 4: Parallel Module Trait

Create `MavenResolverModule` as alternative to `CoursierModule`. Users mix in one or
the other per module.

**Rejected.** Doesn't solve the compliance case (needs global switch, not per-module).
Massive code duplication. Mill's own internal resolution (meta-build) would still use
Coursier.

### Approach 5: Configuration-Based Backend Switch + Worker Isolation (SELECTED)

Combine worker-based architecture (Approach 3) with a global configuration switch.
One config line switches the entire build's resolution backend.

## Selected Approach: Approach 5

### Configuration

```
# In .mill-resolver-backend or build.mill header
//| mill-resolver-backend: maven    # or "coursier" (default)
```

Read early in the launcher (like `mill-jvm-version`). Determines which worker backend
is loaded for the entire build session.

When `maven` is selected:
- All artifact resolution uses Maven Resolver
- JVM management is forced to `system` (no Coursier JVM downloads)
- `~/.m2/settings.xml` is the natural configuration source (repositories, credentials,
  mirrors, proxies) — companies mandating Maven Resolver already have this configured
- No Coursier classes are loaded or invoked

### Module structure

```
mill-resolver-api/           # Tiny module, zero dependencies
  trait ArtifactResolver
  case class ResolveRequest(...)
  case class ResolveResult(...)
  case class FetchRequest(...)
  case class FetchResult(...)
  case class ResolvedArtifact(...)
  case class DependencyGraph(...)
  // Pure data types, fully serializable

mill-resolver-coursier/      # Implements ArtifactResolver via Coursier
  class CoursierArtifactResolver extends ArtifactResolver

mill-resolver-maven/         # Implements ArtifactResolver via Maven Resolver
  class MavenArtifactResolver extends ArtifactResolver
```

Each backend runs in its own classloader (worker pattern).

### Common API surface

| Capability | Supported | Notes |
|---|---|---|
| Resolve transitive deps to jar paths | Yes | Core use case |
| Source/javadoc artifact fetching | Yes | Classifier-based, both support |
| Repository config (URLs, auth, proxies) | Yes | Coursier: env vars / config. Maven: settings.xml |
| Exclusions | Yes | Both support |
| Forced versions | Yes | Both support |
| Offline mode | Yes | Both support local-only policies |
| BOM / dependency management | Yes | Both support, different mechanisms |
| Mirror configuration | Yes | Coursier: env config. Maven: settings.xml mirrors |
| Dependency graph / metadata | Partial | Different internal representations; API exposes simplified graph |
| Progress reporting | Yes | Callback-based, backend-specific impl |

### Coursier-only features (not in common API)

| Feature | Reason |
|---|---|
| JVM index / download (`JvmCache`, `JavaHome`, `JvmIndex`) | Orthogonal to artifact resolution. When Maven backend is active, JVM management is `system`-only |
| Gradle module metadata (`VariantSelector`, `checkGradleModules`) | Coursier-specific extension; Maven repos don't serve Gradle metadata |
| `COURSIER_*` environment variables | Backend-specific configuration; Maven uses `settings.xml` |

### Maven Resolver specific features (available only with Maven backend)

| Feature | Notes |
|---|---|
| Full Maven scope model (compile/runtime/test/provided/system/import) | Coursier has simplified scope handling |
| `settings.xml` integration | Repositories, mirrors, proxies, credentials from standard Maven config |
| Pluggable transports (file, HTTP, S3/MinIO, Wagon) | Via Maven Resolver's SPI |
| Two-phase resolution (collect graph, then download) | Can be exposed as optimisation |
| Strict POM-based resolution | Exact Maven semantics for companies requiring it |

### Key design constraints

1. **The API module has zero external dependencies** — only case classes and traits
2. **`Dep` / `BoundDep` remain as Mill's DSL types** — conversion to backend-specific
   types happens inside the worker boundary
3. **`CoursierModule` stays but delegates to the worker API** — backward compatible
   for existing builds using Coursier
4. **When Maven backend is active, Coursier is not on the classpath at all** — clean
   separation for compliance
5. **Maven backend reads `~/.m2/settings.xml`** — natural config for target audience

### Integration with Mill's architecture

The worker pattern is proven in Mill:

- **Zinc compiler worker** — `ZincWorkerModule` / `JvmWorkerImpl`
- **Maven publish worker** — `MavenWorkerSupport` / `WorkerRemoteM2Publisher`
- **Kotlin compiler worker** — `KotlinWorkerManager`

Each uses `Task.Worker[T]` with classloader isolation. The resolver worker follows
the same pattern: a small API module defines the contract, implementations are loaded
into isolated classloaders based on configuration.

### Scope separation for JVM management

JVM download/management stays as a Coursier-specific feature OR becomes `system`-only
when Maven backend is active. This is correct because:

- Companies mandating Maven Resolver for compliance also mandate system-installed JDKs
- Artifact resolution and JVM management are genuinely independent concerns
- On NixOS (where this work originated), JVM management is already `system`-only

### What needs to change in existing code

1. **`Jvm.scala`** — resolution methods (`resolveDependencies`, `fetchArtifacts`,
   `resolveDependenciesMetadataSafe`) refactored to delegate to the worker API
2. **`CoursierModule.scala`** — `Resolver` class refactored to use worker API
   internally while keeping the same public interface
3. **`CoursierConfigModule.scala`** — becomes one of two config providers; Maven
   backend has its own config reading `settings.xml`
4. **`Dep.scala`** — the `coursier.Dependency` wrapping needs to move behind the API
   boundary; `Dep` should carry Mill-native coordinate data that gets converted inside
   the worker
5. **Launcher** (`MillProcessLauncher.scala`, `CoursierClient.scala`) — needs to
   respect the backend switch for boot-time resolution (Mill's own classpath)

### Open questions for detailed design

- **Meta-build resolution**: Mill's `mill-build/` (the build that builds the build)
  also resolves via Coursier. Does the Maven backend apply there too?
- **Lock files**: Neither backend currently supports lock files. Is this in scope?
- **Error message parity**: Should Maven backend errors match Coursier's format?
- **Incremental migration path**: Can we land the API module first, then Coursier
  adapter, then Maven adapter — in separate PRs?
- **Testing strategy**: Do we run Mill's full test suite on both backends? Subset?
- **Performance**: Maven Resolver's two-phase resolution might be slower/faster for
  different dependency graph shapes. Need benchmarks.
- **Launcher bootstrap**: The `./mill` script downloads Mill itself. When Maven backend
  is configured, should the launcher use Maven Resolver for this too, or is that a
  separate concern?

## Appendix: Coursier API Surface Used by Mill

### Types imported across the codebase

```
coursier.{Artifacts, Classifier, Dependency, Fetch, Repository, Resolution, Resolve, Type}
coursier.cache.{ArchiveCache, CacheLogger, CachePolicy, FileCache}
coursier.core.{BomDependency, Configuration, DependencyManagement, MinimizedExclusions, Resolution, VariantSelector}
coursier.credentials.Credentials
coursier.error.{FetchError, ResolutionError}
coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
coursier.maven.{MavenRepository, MavenRepositoryLike}
coursier.params.{Mirror, ResolutionParams}
coursier.parse.{ModuleParser, RepositoryParser}
coursier.util.{EitherT, EnvEntry, EnvValues, ModuleMatcher, Monad, Task}
coursier.{CoursierEnv, Module, ModuleName, Organization, VersionConstraint}
```

### Maven Resolver API Surface (from `/p/gh/maven-resolver`)

```
org.eclipse.aether.RepositorySystem
org.eclipse.aether.RepositorySystemSession
org.eclipse.aether.artifact.{Artifact, DefaultArtifact}
org.eclipse.aether.graph.{Dependency, DependencyNode, Exclusion}
org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
org.eclipse.aether.collection.{CollectRequest, CollectResult}
org.eclipse.aether.resolution.{ArtifactRequest, ArtifactResult, DependencyRequest, DependencyResult}
org.eclipse.aether.resolution.{VersionRangeRequest, VersionRangeResult}
org.eclipse.aether.version.{Version, VersionRange, VersionConstraint, VersionScheme}
org.eclipse.aether.scope.{DependencyScope, ResolutionScope, ScopeManager}
```
