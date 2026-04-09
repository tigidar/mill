# Maven Resolver Integration: Design Exploration Report

**Date:** 2026-04-09
**Status:** Approach selected, scope refined, pending detailed design
**Selected approach:** Approach 5 — Configuration-based backend switch with worker isolation

## Motivation

Maven Resolver (formerly Eclipse Aether) needs to be a configurable alternative to
Coursier for artifact resolution in Mill. The primary driver is **compliance**.

### The security problem

Coursier is not enterprise-ready from a security and compliance perspective:

1. **OkHttp hostname verification bug** — Coursier uses OkHttp as its HTTP transport.
   OkHttp has a known bug where it does not verify the hostname against the TLS
   certificate when connecting through a proxy. This means anyone using Coursier behind
   a corporate proxy cannot verify they are actually receiving artifacts from the
   intended repository. The Coursier team has acknowledged this and stated it is a
   "no fix" — they do not intend to address it.

2. **No PGP signature verification** — Coursier verifies checksums (integrity) but
   not PGP signatures (authenticity). It is possible to fetch `.asc` files via the API
   but no verification is performed.

3. **Accepts artifacts without checksums** — If no checksum is available, Coursier
   accepts the artifact without verification.

4. **No verification metadata** — No equivalent to Gradle's `verification-metadata.xml`.

5. **Indirect CVE exposure** — CVE-2022-46751 (XML external entity injection) and
   CVE-2022-37866 (path traversal) have impacted Coursier through dependencies.

For compliance-focused organisations, Maven Resolver with `settings.xml` gives them
the entire Maven security ecosystem: repository managers (Nexus/Artifactory) with
policy enforcement, mirror pinning, transport security, and proxy authentication
that their security team already controls and audits.

### Design goal

When Maven Resolver is active, **all HTTP communication for dependency resolution
must go through Maven Resolver** — no OkHttp/Coursier HTTP calls. JVM management
becomes `system`-only, which aligns with the compliance scenario since those
organisations also mandate system-installed JDKs.

## Current State

### Where Coursier makes HTTP calls

A codebase-wide analysis found **48 non-test source files** that reference Coursier
types, but only **5 files** actually make HTTP calls via Coursier's `FileCache`
(which uses OkHttp):

| # | File | What it does | Funnels through `Jvm.resolveDependencies`? |
|---|------|------|---|
| 1 | `libs/util/java11/.../Jvm.scala` | **The main resolution engine** — `resolveDependencies`, `fetchArtifacts`, `resolveDependenciesMetadataSafe`, JVM resolution | This IS the central path |
| 2 | `runner/launcher/CoursierClient.scala` | Mill daemon bootstrap classpath + JVM download | No — launcher level, runs before Mill starts |
| 3 | `libs/javalib/.../dependency/metadata/MavenMetadataLoader.scala` | Dependency update checker — queries `maven-metadata.xml` | No — creates its own `FileCache[Task]()` |
| 4 | `libs/androidlib/.../AndroidSdkModule.scala` | Downloads bundletool.jar | No — creates its own `FileCache()` |
| 5 | `runner/daemon/IdeWorkerSupport.scala` | Resolves IDE plugin classpath | Yes — calls `Jvm.resolveDependencies()` |

**All other 43 files use Coursier only as a data model** — types like
`coursier.core.Configuration`, `coursier.Dependency`, `coursier.Repository`. They
pass these types into `CoursierModule.Resolver.classpath()` or
`Jvm.resolveDependencies()`, which is the single funnel point where actual HTTP
fetching happens.

### Publishing: already uses Maven Resolver

| Publishing path | Transport used | Coursier involved? |
|---|---|---|
| `publishM2Local` | `LocalM2Publisher` — direct file copy | **No** |
| `publishM2Remote` | `MavenWorkerSupport` → Maven Resolver `DeployRequest` | **No** |
| `publishSonatype` | `SonatypeHttpApi` — Mill's own HTTP client | **No** |
| `publish/` package | Mill's own code | **No** — zero Coursier imports |

The artifact upload paths are **already Coursier-free**. The only Coursier leak in
the publishing flow is `PublishModule.ivy()` which calls `millResolver().resolution()`
to compute the transitive dependency graph for POM/ivy.xml metadata generation. This
resolution call funnels through `Jvm.resolveDependenciesMetadataSafe()` — so swapping
the resolution engine covers publishing too.

### How resolution call sites break down

The `CoursierModule.Resolver` class provides 4 methods. Their usage:

| Method | Return type | Call sites | Coursier types in return? |
|---|---|---|---|
| `classpath()` | `Seq[PathRef]` | **90** | **No** — just file paths |
| `resolution()` | `coursier.core.Resolution` | **3** | Yes — deep metadata |
| `artifacts()` | `coursier.Artifacts.Result` | **4** | Yes — `(Dependency, Publication, Artifact, File)` tuples |
| `fetchArtifacts()` | `Fetch.Result` | **0 external** | N/A — only called internally by `classpath()` |

**90 out of 97 call sites (93%) just want file paths back.** They call `classpath()`
which returns `Seq[PathRef]` — no Coursier types in the return value.

The 7 remaining callers:
- `PublishModule.resolution()` — extracts `finalDependenciesCache` and `projectCache`
  for BOM/depManagement handling during POM generation
- `BspJavaModule.resolution()` — dependency graph for BSP
- `AndroidR8AppModule.resolution()` — dependency graph for R8 shrinking
- `QuarkusModule.artifacts()` (3x) — maps resolved artifacts to `(groupId, artifactId, version, path)`
- `NativeImageModule.artifacts()` — inspects artifact GAV coordinates

What the `artifacts()` callers actually extract from the Coursier result:
```
dependency.module.organization.value  → groupId (String)
dependency.module.name.value          → artifactId (String)
dependency.versionConstraint.asString → version (String)
file                                  → resolved path (os.Path)
```

This is trivially producible from Maven Resolver's `DependencyResult`.

### Existing Maven Resolver usage in Mill

Mill already uses Maven Resolver in three places:

- `libs/init/maven/` — POM reading for Maven-to-Mill project migration
- `libs/javalib/maven-worker/` — artifact publishing to Maven repos via `DeployRequest`
- All three use the **worker pattern** with classloader isolation

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

**Reconsidered — viable for Phase 1.** Initially rejected because Coursier types remain
visible when Maven is active. However, the call site analysis shows that 93% of callers
only see `Seq[PathRef]` (no Coursier types in return). For Phase 1, translating Maven
Resolver results back to Coursier types at the `Jvm.scala` boundary is pragmatic and
minimises blast radius. Coursier types remain as a data model but no Coursier HTTP code
executes.

### Approach 3: Worker-Isolated Resolver with Simple API

Define a serializable API boundary in a small module. Each backend (Coursier, Maven)
implements it in its own classloader via Mill's worker pattern.

**Viable as the long-term architecture (Phase 2).** Clean isolation, follows Mill's
established patterns. Not needed for Phase 1 since the adapter approach works for the
compliance case.

### Approach 4: Parallel Module Trait

Create `MavenResolverModule` as alternative to `CoursierModule`.

**Rejected.** Doesn't solve the compliance case (needs global switch, not per-module).

### Approach 5: Configuration-Based Backend Switch (SELECTED)

Global configuration determines which resolution backend is used. The implementation
strategy is phased: Phase 1 uses an adapter at `Jvm.scala` with Coursier types as data
model; Phase 2 optionally moves to full worker isolation.

## Selected Approach: Detailed Design

### Configuration

```
# In .mill-resolver-backend or build.mill header
//| mill-resolver-backend: maven    # or "coursier" (default)
```

Read early in the launcher (like `mill-jvm-version`). Determines which resolution
backend is used for the entire build session.

When `maven` is selected:
- All dependency resolution and artifact fetching uses Maven Resolver
- JVM management is forced to `system` (no Coursier JVM downloads)
- Repository config read from `~/.m2/settings.xml`
- No Coursier HTTP calls are made (OkHttp never invoked)
- Coursier types remain on the classpath as a data model only (Phase 1)

### What needs to change — definitive scope

#### Primary target: `Jvm.scala` (covers 97 call sites + publishing metadata)

All resolution funnels through three methods in `Jvm.scala`. Each needs a backend
switch:

**`resolveDependencies()`** — used by 90 call sites via `Resolver.classpath()`
- Input: `Seq[coursier.Dependency]`, `Seq[Repository]`, config
- Output: `Result[Seq[PathRef]]` — just file paths
- Maven backend: convert deps to Maven Resolver types → resolve → return paths
- Straightforward, no Coursier types in return

**`fetchArtifacts()`** — used by 4 call sites via `Resolver.artifacts()`
- Input: same as above
- Output: `Result[Fetch.Result]` with `detailedArtifacts0: Seq[(Dependency, Publication, Artifact, File)]`
- Maven backend: construct `Fetch.Result` from Maven's `DependencyResult`
- Callers only extract `(org, name, version, path)` — moderate effort

**`resolveDependenciesMetadataSafe()`** — used by 3 call sites via `Resolver.resolution()`
- Input: same as above
- Output: `Result[coursier.core.Resolution]` with `finalDependenciesCache`, `projectCache`
- Maven backend: construct `Resolution` from Maven's dependency graph
- Hardest part — `PublishModule` reaches deep into resolution metadata
- Can be implemented incrementally (start with the fields PublishModule actually reads)

#### Secondary target: `CoursierClient.scala` (launcher bootstrap)

- `resolveMillDaemonDeps()` — resolves Mill's own daemon classpath at startup
- `resolveJavaHome()` — downloads JDK distributions
- Maven backend: use Maven Resolver for daemon deps, force `system` for JVM
- Runs before Mill's build evaluator, so worker pattern doesn't apply; needs
  direct Maven Resolver integration or a pre-built classpath

#### Minor outliers (can defer)

| File | Risk | Priority |
|------|------|----------|
| `MavenMetadataLoader.scala` | Dependency update checker, user-triggered command | Low — non-critical feature |
| `AndroidSdkModule.scala` | Downloads bundletool.jar, Android-specific | Low — niche use case |

#### No change needed

| Concern | Why |
|---|---|
| **Publishing (M2 local)** | Already uses `LocalM2Publisher` — file copy |
| **Publishing (M2 remote)** | Already uses Maven Resolver via `MavenWorkerSupport` |
| **Publishing (Sonatype)** | Already uses Mill's own `SonatypeHttpApi` |
| **Consumer modules (40+ files)** | Only use Coursier as data model, all resolution funnels through `Jvm.scala` |
| **`Dep` / `BoundDep` types** | Wrap `coursier.Dependency` but conversion happens before `Jvm.scala` entry point |

### Implementation phasing

#### Phase 1: Backend switch at `Jvm.scala` boundary (minimal change)

**Goal:** No Coursier HTTP calls when Maven backend is active. Coursier types remain
as data model.

**Files changed:**
- `Jvm.scala` — add backend switch in `resolveDependencies`, `fetchArtifacts`,
  `resolveDependenciesMetadataSafe`; add `MavenResolverBackend` implementation
  (new file or inline)
- `CoursierClient.scala` — add backend switch for daemon bootstrap
- Configuration reading in launcher

**What the Maven backend does:**
1. Receives `Seq[coursier.Dependency]` and `Seq[coursier.Repository]`
2. Converts to Maven Resolver types (`DefaultArtifact`, `Dependency`, `RemoteRepository`)
3. Creates `RepositorySystem` + session from `settings.xml`
4. Calls `collectDependencies()` → `resolveDependencies()`
5. Converts results back:
   - For `classpath()`: just `Seq[os.Path]` → `Seq[PathRef]` (trivial)
   - For `artifacts()`: `Seq[(coursier.Dependency, Publication, Artifact, File)]`
     constructed from `DependencyResult` (moderate)
   - For `resolution()`: partial `coursier.core.Resolution` with
     `finalDependenciesCache` and `projectCache` populated (hardest, scoped to
     what `PublishModule` actually reads)

**Blast radius:** 2-3 existing files modified, 1-2 new files added. Zero changes to
the 40+ consumer modules.

#### Phase 2: Worker isolation + native types (optional, long-term)

**Goal:** Full Coursier removal from classpath when Maven backend is active. Clean
API boundary.

**Additional work:**
- Define `mill-resolver-api` module with backend-agnostic types
- Detach `Dep` from `coursier.Dependency` — replace internal representation with
  Mill-native coordinate data
- Move Coursier and Maven Resolver into separate worker classloaders
- Estimated ~20 files for the `Dep` migration (mechanical changes)

**Phase 2 is not required for the compliance case** but provides full separation.

### Coursier coupling analysis

#### Where HTTP calls originate (the security-relevant surface)

```
HTTP calls via Coursier/OkHttp:
  Jvm.scala                    ████████████████  ← 97 callers funnel here
  CoursierClient.scala         ████████          ← launcher bootstrap
  MavenMetadataLoader.scala    ██                ← dep update checker (minor)
  AndroidSdkModule.scala       █                 ← bundletool download (minor)

Already safe (no Coursier HTTP):
  Publishing (M2 local)        ████████████████  LocalM2Publisher (file copy)
  Publishing (M2 remote)       ████████████████  Maven Resolver DeployRequest
  Publishing (Sonatype)        ████████████████  Mill's own SonatypeHttpApi
```

#### Where Coursier types appear (data model, NOT security-relevant)

48 files reference Coursier types, but only as data structures. These do NOT make
HTTP calls and do NOT need to change for Phase 1:

```
Data model usage (no HTTP, no change needed for Phase 1):
  Dep.scala                    ████████  wraps coursier.Dependency
  JavaModule.scala             ██████    coursier.core.Configuration, Resolution
  ScalaModule.scala            ████      mapDependencies signature
  PublishModule.scala          ████      DependencyManagement for POM gen
  CoursierModule.scala         ████████  Resolution, ResolutionParams
  12+ consumer modules         ██        Configuration.provided, Type, etc.
```

#### Call site distribution

```
Resolver.classpath()     ████████████████████████████████████████  90 sites (93%)
  → returns Seq[PathRef] — NO Coursier types in return

Resolver.resolution()    ███                                       3 sites (3%)
  → returns coursier.core.Resolution — deep metadata
  → callers: PublishModule, BspJavaModule, AndroidR8AppModule

Resolver.artifacts()     ████                                      4 sites (4%)
  → returns coursier.Artifacts.Result — (dep, pub, artifact, file)
  → callers: NativeImageModule, QuarkusModule(3x)
  → callers only extract (org, name, version, path) — trivially mapped
```

### Open questions for detailed design

- **Meta-build resolution**: Mill's `mill-build/` (the build that builds the build)
  also resolves via Coursier. Does the Maven backend apply there too?
- **Error message parity**: Should Maven backend errors match Coursier's format?
- **Incremental delivery**: Can we land Phase 1 in stages — `classpath()` first (90
  sites), then `artifacts()` (4 sites), then `resolution()` (3 sites)?
- **Testing strategy**: Do we run Mill's full test suite on both backends? Subset?
- **Performance**: Maven Resolver's two-phase resolution might be slower/faster for
  different dependency graph shapes. Need benchmarks.
- **Launcher bootstrap**: `CoursierClient.scala` runs before the build evaluator. Does
  Maven Resolver need to be bootstrapped without worker infrastructure?
- **`settings.xml` scope**: Should Mill read the full `settings.xml` (servers, mirrors,
  proxies, profiles) or a subset?

## Appendix: Coursier Security Documentation

From Coursier's official security page:

- **Checksum verification**: MD5, SHA-1, SHA-256 supported. If no checksum available,
  artifact accepted without verification.
- **Signature verification**: Not performed. `.asc` files can be fetched but not verified.
- **Verification metadata**: Not supported (no equivalent to Gradle's
  `verification-metadata.xml`).
- **CVEs**: Indirectly impacted by CVE-2022-46751 and CVE-2022-37866.
- **OkHttp hostname verification**: Not documented on the page but acknowledged by
  maintainers as "no fix" — hostname not verified against certificate behind proxy.

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
