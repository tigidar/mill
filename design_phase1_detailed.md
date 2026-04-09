# Phase 1 Detailed Design: Maven Resolver Backend at `Jvm.scala` Boundary

**Date:** 2026-04-09
**Prerequisite:** [design_exploration_report.md](design_exploration_report.md)
**Scope:** Backend switch in `Jvm.scala` + launcher; Coursier types remain as data model

---

## 1. Overview

Phase 1 adds a `maven` resolution backend behind a global configuration flag. When
active, all dependency resolution and artifact fetching uses Maven Resolver (Eclipse
Aether). No Coursier HTTP calls are made. Coursier types remain on the classpath as a
data model — Maven Resolver results are converted back to Coursier types at the
`Jvm.scala` boundary so that the 97 call sites and 40+ consumer modules require zero
changes.

### Design principles

1. **Minimal blast radius** — only `Jvm.scala`, `CoursierClient.scala`, configuration
   plumbing, and one new worker module are modified or added.
2. **Worker isolation** — Maven Resolver JARs load in a separate classloader, following
   Mill's established worker pattern (same pattern as `maven-worker` for publishing).
3. **Adapter at the boundary** — the three methods in `Jvm.scala` that make HTTP calls
   gain a backend switch. Maven results are converted to Coursier types before returning.
4. **Configuration-first** — a single global setting determines the backend for the
   entire build session, read at launcher time.

---

## 2. Configuration Mechanism

### 2.1 New config key: `mill-resolver-backend`

Add to `ConfigConstants.java`:
```java
public static final String millResolverBackend = "mill-resolver-backend";
```

Register in `ConfigConstants.all()`.

### 2.2 How it's specified

Following the established pattern used by `mill-jvm-version`:

**Option A — Dot file** (`.mill-resolver-backend`):
```
maven
```

**Option B — Build header** (YAML or `//|` syntax):
```yaml
# build.mill.yaml
mill-resolver-backend: maven
```
```scala
// build.mill
//| mill-resolver-backend: maven
```

Values: `coursier` (default), `maven`.

### 2.3 Where it's read

**Launcher level** — `MillProcessLauncher` reads it alongside `mill-jvm-version`:

```scala
val resolverBackend = MillProcessLauncher
  .loadMillConfig(ConfigConstants.millResolverBackend, workDir)
  .headOption
  .getOrElse("coursier")
```

This value must be threaded into two places:

1. **`CoursierClient`** — for launcher-time resolution (daemon classpath, JVM download).
   Passed as parameter to `resolveMillDaemon()` and `resolveJavaHome()`.
2. **Mill daemon** — passed as a system property (`-Dmill.resolver.backend=maven`) so
   the evaluator can read it. Alternatively stored in `BuildCtx` analogous to
   `millRepositories`.

### 2.4 Propagation to the evaluator

Inside the Mill daemon process, the backend value is available via:

```scala
// In mill.util or mill.api
object ResolverBackend {
  def current: String = sys.props.getOrElse("mill.resolver.backend", "coursier")
  def isMaven: Boolean = current == "maven"
}
```

This is read by `Jvm.scala` methods to choose the backend. It does NOT need to flow
through `CoursierConfig` or task parameters — it's a process-global invariant for the
build session, analogous to the JVM version.

### 2.5 Selective execution / cache invalidation

Add `millResolverBackend: String = ""` to:
- `Cached` (`core/api/src/mill/api/internal/Cached.scala`)
- `SelectiveExecution.Metadata` (`core/api/src/mill/api/SelectiveExecution.scala`)

Add invalidation check in `GroupExecution.scala` and `SelectiveExecutionImpl.scala`
following the `millJvmVersion` pattern:
```scala
checkMatch(cached.millResolverBackend, currentResolverBackend, "mill-resolver-backend-changed")
```

This ensures that switching backends invalidates all cached resolution results.

### 2.6 JVM management constraint

When `maven` backend is active:
- `resolveJavaHome()` in `CoursierClient` is **disabled** — Mill requires a
  system-installed JVM.
- If no system JVM is available, fail with a clear error:
  `"Maven resolver backend requires a system-installed JVM. Set mill-jvm-version: system or ensure JAVA_HOME is set."`
- This avoids the need to reimplement Coursier's JVM index/download infrastructure.

---

## 3. New Module: `maven-resolver-worker`

### 3.1 Module definition

```
libs/javalib/maven-resolver-worker/
  package.mill
  src/mill/javalib/maven/resolver/worker/impl/
    MavenResolverWorkerImpl.scala
    MavenResolverEngine.scala
    CoursierTypeAdapter.scala
```

In `libs/javalib/package.mill`:
```scala
object `maven-resolver-worker` extends MillPublishScalaModule {
  override def moduleDeps = Seq(build.libs.javalib)
  override def mvnDeps = {
    val mavenResolverVersion = "2.0.10"
    val mavenVersion = "3.9.9"
    Seq(
      mvn"org.apache.maven.resolver:maven-resolver-api:$mavenResolverVersion",
      mvn"org.apache.maven.resolver:maven-resolver-util:$mavenResolverVersion",
      mvn"org.apache.maven.resolver:maven-resolver-connector-basic:$mavenResolverVersion",
      mvn"org.apache.maven.resolver:maven-resolver-supplier:2.0.0-alpha-8",
      mvn"org.apache.maven.resolver:maven-resolver-transport-http:2.0.0-alpha-2",
      mvn"org.apache.maven.resolver:maven-resolver-transport-file:$mavenResolverVersion",
      mvn"org.apache.maven:maven-settings:$mavenVersion",
      mvn"org.apache.maven:maven-settings-builder:$mavenVersion"
    )
  }
}
```

### 3.2 Why a separate worker module

Maven Resolver has its own transitive dependencies (Apache HttpClient, SLF4J bindings,
Plexus, etc.) that conflict with Mill's classpath. The existing `maven-worker` module
for publishing already uses this pattern for exactly this reason.

The worker runs in its own classloader. The API boundary between Mill and the worker
is a simple trait with serializable types.

### 3.3 Worker API

Defined in `libs/javalib/src/mill/javalib/internal/MavenResolverSupport.scala`:

```scala
package mill.javalib.internal

object MavenResolverSupport {

  /** API boundary between Mill and the Maven Resolver worker classloader. */
  trait Api extends AutoCloseable {

    /**
     * Resolve dependencies and return paths to downloaded artifacts.
     *
     * @param deps      sequence of (groupId, artifactId, version, configuration, exclusions)
     * @param repositories sequence of (id, url) pairs; empty = read from settings.xml
     * @param sources   whether to fetch source JARs
     * @param forceVersions map of (groupId:artifactId -> version) for forced versions
     * @param bomDeps   sequence of (groupId, artifactId, version) for BOM imports
     * @return sequence of absolute paths to resolved JAR files
     */
    def resolveClasspath(
        deps: Seq[DependencyCoord],
        repositories: Seq[RepoCoord],
        sources: Boolean,
        forceVersions: Map[String, String],
        bomDeps: Seq[BomCoord]
    ): Seq[String]

    /**
     * Resolve dependencies and return detailed artifact information.
     *
     * @return sequence of (groupId, artifactId, version, classifier, path) tuples
     */
    def resolveArtifacts(
        deps: Seq[DependencyCoord],
        repositories: Seq[RepoCoord],
        sources: Boolean,
        forceVersions: Map[String, String],
        bomDeps: Seq[BomCoord]
    ): Seq[ResolvedArtifact]

    /**
     * Resolve dependencies and return the full dependency graph metadata.
     *
     * @return resolution metadata including transitive deps, project cache, and BOM overrides
     */
    def resolveMetadata(
        deps: Seq[DependencyCoord],
        repositories: Seq[RepoCoord],
        forceVersions: Map[String, String],
        bomDeps: Seq[BomCoord]
    ): ResolutionMetadata
  }

  // --- Serializable data types crossing the classloader boundary ---

  case class DependencyCoord(
      groupId: String,
      artifactId: String,
      version: String,
      configuration: String,         // "compile", "runtime", "provided", etc.
      exclusions: Seq[(String, String)], // (groupId, artifactId) pairs
      classifier: String,
      `type`: String
  )

  case class RepoCoord(
      id: String,
      url: String,
      `type`: String                  // "default" for Maven repos
  )

  case class BomCoord(
      groupId: String,
      artifactId: String,
      version: String
  )

  case class ResolvedArtifact(
      groupId: String,
      artifactId: String,
      version: String,
      classifier: String,
      path: String                    // absolute file path
  )

  case class ResolutionMetadata(
      /** root dep key -> all transitive dep coords (flat) */
      transitiveDeps: Map[String, Seq[ResolvedDep]],
      /** (groupId:artifactId:version) -> project info with BOM overrides */
      projectCache: Map[String, ProjectInfo],
      /** all resolved deps in resolution order */
      orderedDeps: Seq[ResolvedDep]
  )

  case class ResolvedDep(
      groupId: String,
      artifactId: String,
      version: String,
      configuration: String
  )

  case class ProjectInfo(
      /** BOM/depManagement overrides: (groupId, artifactId, type, classifier) -> version */
      overrides: Seq[((String, String, String, String), String)],
      /** license info: (name, url) pairs */
      licenses: Seq[(String, String)],
      /** parent POM coordinates, if any */
      parent: Option[(String, String, String)]
  )
}
```

Key design decisions:
- **All types are plain Scala case classes with String/Seq fields** — no Coursier types,
  no Maven Resolver types. They cross the classloader boundary via the shared parent
  classloader (Mill's own classloader has these types).
- **Three methods mirror the three Jvm.scala entry points** — `resolveClasspath` for
  `resolveDependencies`, `resolveArtifacts` for `fetchArtifacts`/`getArtifacts`,
  `resolveMetadata` for `resolveDependenciesMetadataSafe`.

---

## 4. Worker Implementation

### 4.1 `MavenResolverEngine.scala`

Core resolution logic inside the worker classloader:

```scala
package mill.javalib.maven.resolver.worker.impl

import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.{Dependency, Exclusion}
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.resolution.{DependencyRequest, DependencyResult}
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.util.artifact.JavaScopes

class MavenResolverEngine extends AutoCloseable {
  private val system: RepositorySystem = RepositorySystemSupplier().get()

  def createSession(localRepoPath: java.nio.file.Path): DefaultRepositorySystemSession = {
    val session = DefaultRepositorySystemSession()
    val localRepo = LocalRepository(localRepoPath)
    session.setLocalRepositoryManager(
      system.newLocalRepositoryManager(session, localRepo)
    )
    session
  }

  def resolve(
      session: DefaultRepositorySystemSession,
      deps: Seq[Dependency],
      remotes: Seq[RemoteRepository]
  ): DependencyResult = {
    val collectRequest = CollectRequest()
    deps.foreach(collectRequest.addDependency)
    remotes.foreach(collectRequest.addRepository)

    val depRequest = DependencyRequest(collectRequest, null)
    system.resolveDependencies(session, depRequest)
  }

  def close(): Unit = system.close()
}
```

### 4.2 `settings.xml` integration

The worker reads `~/.m2/settings.xml` for:
- **Repositories** — `<mirrors>`, `<profiles>` with `<repositories>`
- **Servers** — credentials for authenticated repos
- **Proxies** — HTTP/HTTPS proxy configuration

Using Maven Settings Builder (already in dependencies):

```scala
import org.apache.maven.settings.building._

def loadSettings(): Settings = {
  val request = DefaultSettingsBuildingRequest()
  request.setGlobalSettingsFile(new File("/etc/maven/settings.xml"))
  request.setUserSettingsFile(new File(System.getProperty("user.home"), ".m2/settings.xml"))
  request.setSystemProperties(System.getProperties)

  val builder = DefaultSettingsBuilderFactory().newInstance()
  builder.build(request).getEffectiveSettings
}
```

Repository list construction:
1. If Mill build specifies repositories via `repositoriesTask`/`mill-repositories`,
   those are passed as `RepoCoord` and used directly.
2. If repositories list is empty (or contains only default), merge with `settings.xml`
   mirrors and profiles.
3. Server credentials from `settings.xml` are matched by repository ID and applied via
   `AuthenticationBuilder`.

### 4.3 `CoursierTypeAdapter.scala`

Converts Maven Resolver results back to Coursier types at the API boundary. This is the
core of the Phase 1 adapter strategy.

```scala
package mill.javalib.maven.resolver.worker.impl

// This file lives in Jvm.scala's module (NOT the worker), because it
// constructs Coursier types which are on Mill's classpath.
// It consumes the worker API's plain types.
```

Wait — important architectural point: the adapter **cannot** live in the worker module
because the worker classloader doesn't have Coursier types. It must live in `Jvm.scala`'s
module (`libs/util/java11`) or in `libs/javalib`.

**Correction:** `CoursierTypeAdapter` lives alongside `Jvm.scala` in `libs/util/java11/`
and converts the worker API's plain types back to Coursier types.

---

## 5. Backend Switch in `Jvm.scala`

### 5.1 Entry point pattern

Each of the three resolution methods in `Jvm.scala` gains a backend check at the top:

```scala
def resolveDependencies(
    repositories: Seq[Repository],
    deps: IterableOnce[Dependency],
    force: IterableOnce[Dependency],
    // ... all existing parameters ...
): Result[Seq[PathRef]] = {
  if (ResolverBackend.isMaven)
    resolveDependenciesMaven(repositories, deps, force, sources, ctx, artifactTypes, resolutionParams, config, boms)
  else
    resolveDependenciesCoursier(repositories, deps, force, sources, mapDependencies, customizer, ctx, coursierCacheCustomizer, artifactTypes, resolutionParams, checkGradleModules, config, boms)
}
```

The existing Coursier implementation is extracted into a `private def ...Coursier()`
method (pure rename, zero logic change). The new `private def ...Maven()` method handles
the Maven path.

### 5.2 `resolveDependencies` — Maven path

**Input conversion** (Coursier → worker API):
```scala
private def resolveDependenciesMaven(
    repositories: Seq[Repository],
    deps: IterableOnce[Dependency],
    force: IterableOnce[Dependency],
    sources: Boolean,
    ctx: Option[mill.api.TaskCtx],
    artifactTypes: Option[Set[Type]],
    resolutionParams: ResolutionParams,
    config: CoursierConfig,
    boms: IterableOnce[BomDependency]
): Result[Seq[PathRef]] = {
  val depCoords = deps.iterator.map(coursierDepToCoord).toSeq
  val repoCoords = repositories.collect {
    case m: MavenRepository => RepoCoord(m.root, m.root, "default")
  }
  val forceVersions = force.iterator.map { d =>
    s"${d.module.organization.value}:${d.module.name.value}" -> d.version.asString
  }.toMap
  val bomCoords = boms.iterator.map(coursierBomToCoord).toSeq

  val paths = mavenWorker().resolveClasspath(
    depCoords, repoCoords, sources, forceVersions, bomCoords
  )

  Result.Success(
    BuildCtx.withFilesystemCheckerDisabled {
      paths.map(p => PathRef(os.Path(p), quick = true))
    }
  )
}
```

**90 call sites covered.** Return type `Seq[PathRef]` — no Coursier types exposed.

### 5.3 `fetchArtifacts` / `getArtifacts` — Maven path

**Returns `coursier.Fetch.Result` / `coursier.Artifacts.Result`:**

These types require constructing Coursier `Dependency`, `Publication`, and `Artifact`
objects from the Maven results. The adapter:

```scala
private def fetchArtifactsMaven(...): Result[coursier.Fetch.Result] = {
  val resolved = mavenWorker().resolveArtifacts(depCoords, repoCoords, sources, forceVersions, bomCoords)
  val resolution = resolveDependenciesMetadataSafeMaven(...)  // get Resolution too

  val detailedArtifacts = resolved.map { ra =>
    val dep = coursier.Dependency(
      coursier.Module(Organization(ra.groupId), ModuleName(ra.artifactId)),
      VersionConstraint.fromString(ra.version)
    )
    val pub = coursier.core.Publication(
      name = ra.artifactId,
      `type` = Type.jar,
      ext = coursier.core.Extension.jar,
      classifier = Classifier(ra.classifier)
    )
    val artifact = coursier.core.Artifact(
      url = ra.path,  // file:// URL
      checksumUrls = Map.empty,
      extra = Map.empty,
      changing = false,
      optional = false,
      authentication = None
    )
    (dep, pub, artifact, new java.io.File(ra.path))
  }

  Result.Success(coursier.Fetch.Result(
    resolution.get,
    detailedArtifacts,
    Nil  // fullExtraArtifacts
  ))
}
```

**4 `artifacts()` call sites + 0 `fetchArtifacts()` external call sites covered.**

What callers actually extract (verified from source):

| Caller | Fields accessed | Conversion complexity |
|--------|----------------|----------------------|
| `QuarkusModule` (3x) | `dependency.module.organization.value`, `.name.value`, `.versionConstraint.asString`, `file` | Trivial |
| `NativeImageModule` | `dependency`, `file` (later accesses org/name/version) | Trivial |

### 5.4 `resolveDependenciesMetadataSafe` — Maven path

**Returns `coursier.core.Resolution`:**

This is the hardest conversion. Callers access deep Coursier internals:

| Caller | Field accessed | What it means |
|--------|---------------|---------------|
| `PublishModule` | `resolution.finalDependenciesCache.getOrElse(dep, ...)` | Root dep → flat list of all transitive deps |
| `PublishModule` | `resolution.projectCache.get(dep.moduleVersion)._2.overrides` | BOM dependency management map for the root dep |
| `BspJavaModule` | `resolution.orderedDependencies` | All resolved deps in order |
| `AndroidR8AppModule` | `resolution.dependencies` | All resolved deps (as set) |
| `CycloneDXJavaModule` (contrib) | `resolution.projectCache0.get(module -> version)._2.info.licenseInfo` | License metadata from POM |

**Adapter strategy — construct a real `coursier.core.Resolution`:**

Coursier's `Resolution` is a case class. We can construct one with the fields populated
from Maven Resolver's `DependencyResult`:

```scala
private def resolveDependenciesMetadataSafeMaven(
    repositories: Seq[Repository],
    deps: IterableOnce[Dependency],
    force: IterableOnce[Dependency],
    resolutionParams: ResolutionParams,
    boms: IterableOnce[BomDependency],
    config: CoursierConfig
): Result[Resolution] = {
  val metadata = mavenWorker().resolveMetadata(depCoords, repoCoords, forceVersions, bomCoords)

  // Build finalDependenciesCache
  val finalDepsCache: Map[Dependency, Seq[Dependency]] =
    metadata.transitiveDeps.map { case (rootKey, transitiveDeps) =>
      coordToDependency(rootKey) -> transitiveDeps.map(resolvedDepToCoursierDep)
    }

  // Build projectCache
  val projCache: Map[(Module, VersionConstraint), (ArtifactSource, Project)] =
    metadata.projectCache.map { case (key, info) =>
      val (groupId, artifactId, version) = parseKey(key)
      val module = Module(Organization(groupId), ModuleName(artifactId))
      val vc = VersionConstraint.fromString(version)
      val overrides = info.overrides.map { case ((g, a, t, c), v) =>
        DependencyManagement.Key(Organization(g), ModuleName(a), Type(t), Classifier(c)) ->
          DependencyManagement.Values(Configuration.empty, VersionConstraint.fromString(v), MinimizedExclusions.zero, false)
      }.toMap
      val project = Project(
        module, vc, Nil, Map.empty, None, None, Nil, None,
        info.licenses.map { case (name, url) => Info.License(name, Some(url)) },
        // ... other fields with sensible defaults
        overrides = Seq(overrides)
      )
      (module -> vc) -> (ArtifactSource.empty -> project)
    }

  // Build orderedDependencies (for BspJavaModule)
  val ordered: Seq[Dependency] = metadata.orderedDeps.map(resolvedDepToCoursierDep)

  // Construct Resolution
  val resolution = Resolution()
    .withFinalDependenciesCache(finalDepsCache)
    .withProjectCache(projCache)
    .withOrderedDependencies(ordered)
    .withDependencies(ordered.toSet)

  Result.Success(resolution)
}
```

**Risk assessment:** Constructing a `Resolution` with only the fields callers actually
use means some fields will be empty/default. This is acceptable because:
- We've verified the exact fields each caller reads (see table above)
- No caller iterates over all Resolution fields
- We can add fields incrementally as needed

**3 `resolution()` call sites + 1 contrib (`CycloneDXJavaModule`) covered.**

### 5.5 Parameters not applicable to Maven backend

Several Coursier-specific parameters have no Maven equivalent:

| Parameter | Maven handling |
|-----------|---------------|
| `mapDependencies: Option[Dependency => Dependency]` | Applied before conversion — transform the Coursier `Dependency` objects then convert |
| `customizer: Option[Resolution => Resolution]` | Applied after construction — transform the synthetic Resolution |
| `coursierCacheCustomizer` | Ignored — Maven uses its own HTTP transport |
| `checkGradleModules` | Ignored — Maven doesn't support Gradle module metadata |
| `config.mirrors` | Mapped to Maven `<mirror>` settings via settings.xml |
| `config.credentials` | Mapped to Maven `<server>` settings |
| `config.cachePolicies` | Mapped to Maven session offline/update policies |
| `config.ttl` | Mapped to Maven update policy (always/daily/interval/never) |

### 5.6 Coursier → Worker type conversion helpers

In `Jvm.scala` (or a small companion object):

```scala
private def coursierDepToCoord(dep: Dependency): DependencyCoord =
  DependencyCoord(
    groupId = dep.module.organization.value,
    artifactId = dep.module.name.value,
    version = dep.versionConstraint.asString,
    configuration = dep.configuration.value,
    exclusions = dep.minimizedExclusions.toSeq.map(e =>
      (e.organization.value, e.name.value)
    ),
    classifier = dep.attributes.classifier.value,
    `type` = dep.attributes.`type`.value
  )

private def coursierBomToCoord(bom: BomDependency): BomCoord =
  BomCoord(
    groupId = bom.module.organization.value,
    artifactId = bom.module.name.value,
    version = bom.versionConstraint.asString
  )
```

---

## 6. Worker Loading Infrastructure

### 6.1 `MavenResolverSupport.scala`

In `libs/javalib/src/mill/javalib/MavenResolverSupport.scala`, following the exact
pattern of the existing `MavenWorkerSupport`:

```scala
package mill.javalib

import mill.*

trait MavenResolverSupport extends mill.Module {

  private def mavenResolverWorkerClasspath: T[Seq[PathRef]] = Task {
    // Must resolve via Coursier (the default resolver) even when maven backend is selected,
    // because this resolves Mill's own internal worker classpath, not user dependencies.
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-maven-resolver-worker")
    ))
  }

  private def mavenResolverWorkerClassloader: Task.Worker[ClassLoader & AutoCloseable] =
    Task.Worker {
      val classPath = mavenResolverWorkerClasspath().map(_.path)
      Jvm.createClassLoader(classPath = classPath, parent = getClass.getClassLoader)
    }

  private[mill] def mavenResolverWorker:
      Task.Worker[mill.javalib.internal.MavenResolverSupport.Api] =
    Task.Worker {
      mavenResolverWorkerClassloader()
        .loadClass("mill.javalib.maven.resolver.worker.impl.MavenResolverWorkerImpl")
        .getConstructor()
        .newInstance()
        .asInstanceOf[mill.javalib.internal.MavenResolverSupport.Api]
    }
}
```

### 6.2 Where to mix in `MavenResolverSupport`

`CoursierModule` already extends `mill.Module`. Add `MavenResolverSupport` as a mixin:

```scala
trait CoursierModule extends mill.Module with MavenResolverSupport {
```

This makes `mavenResolverWorker` available to all modules that use resolution.

### 6.3 Worker lifecycle

The worker is a `Task.Worker` — Mill keeps it alive for the duration of the build
session and reuses it across tasks. The `MavenResolverEngine` inside holds a
`RepositorySystem` instance which is expensive to create but reusable.

### 6.4 Passing the worker to `Jvm.scala`

`Jvm.scala` is a utility object, not a module — it doesn't have access to Mill's task
system directly. The worker reference must be passed through the call chain.

**Option A — Thread through parameters** (explicit, verbose):
Add `mavenResolverWorker: Option[MavenResolverSupport.Api] = None` parameter to all
three `Jvm.scala` methods. `Lib.scala` and `CoursierModule.Resolver` pass it through.

**Option B — Thread-local / BuildCtx** (implicit, minimal parameter changes):
Store the worker reference in `BuildCtx` (Mill's thread-local build context) when the
Maven backend is active. `Jvm.scala` reads it from there.

**Recommendation:** Option A for Phase 1 clarity. The parameter is `Option`-typed and
defaults to `None`, so all existing callers continue to work unchanged. Only
`CoursierModule.Resolver` passes the worker when the backend is `maven`.

Actually, on reflection, there's a simpler path: `Jvm.scala` methods already receive
`config: CoursierConfig`. We can add the worker reference to `CoursierConfig` (or create
a parallel `ResolverConfig` that extends it):

```scala
final case class CoursierConfig(
    // ... existing fields ...
    mavenResolverWorker: Option[MavenResolverSupport.Api] = None
)
```

This piggybacks on the existing parameter threading without adding new parameters.

---

## 7. Launcher Changes (`CoursierClient.scala`)

### 7.1 `resolveMillDaemon()`

The daemon classpath resolution runs **before** the Mill evaluator starts, so the worker
pattern is not available. The launcher has its own classloader and runs in a separate
process from the Mill daemon.

**The launcher must also use Maven Resolver when the `maven` backend is active.**

Rationale: the launcher resolves `mill-runner-daemon` through the same proxy as user
dependencies. The OkHttp hostname verification bug applies identically — a MITM at the
proxy could serve a trojaned Mill daemon JAR. This is actually worse than a compromised
user dependency because the daemon process has full access to source code, environment
variables, CI secrets, and credentials. The attack would be silent (no certificate
warnings, cached after first fetch) and nearly impossible to notice.

**Implementation: direct Maven Resolver in the launcher module.**

Add Maven Resolver dependencies to the launcher module (`runner/launcher/package.mill`):

```scala
// Conditional: only included when maven backend may be used
val mavenResolverVersion = "2.0.10"
val mavenVersion = "3.9.9"
Seq(
  mvn"org.apache.maven.resolver:maven-resolver-api:$mavenResolverVersion",
  mvn"org.apache.maven.resolver:maven-resolver-util:$mavenResolverVersion",
  mvn"org.apache.maven.resolver:maven-resolver-connector-basic:$mavenResolverVersion",
  mvn"org.apache.maven.resolver:maven-resolver-supplier:2.0.0-alpha-8",
  mvn"org.apache.maven.resolver:maven-resolver-transport-http:2.0.0-alpha-2",
  mvn"org.apache.maven.resolver:maven-resolver-transport-file:$mavenResolverVersion",
  mvn"org.apache.maven:maven-settings:$mavenVersion",
  mvn"org.apache.maven:maven-settings-builder:$mavenVersion"
)
```

The launcher already runs in its own JVM process (separate from the Mill daemon), so
classpath conflicts with Coursier are not a concern — both can coexist in the launcher.

```scala
def resolveMillDaemon(
    outMode: OutFolderMode,
    millRepositories: Seq[String],
    resolverBackend: String
): Seq[os.Path] = {
  if (resolverBackend == "maven")
    resolveMillDaemonMaven(outMode, millRepositories)
  else
    resolveMillDaemonCoursier(outMode, millRepositories)
}
```

The Maven path:
1. Creates `RepositorySystem` via `RepositorySystemSupplier`
2. Reads `settings.xml` for mirrors, credentials, proxies
3. Configures repositories from `millRepositories` + settings.xml
4. Resolves `com.lihaoyi:mill-runner-daemon_3:{millVersion}` and transitive deps
5. Returns artifact paths

This reuses the same `MavenResolverEngine` and `SettingsXmlReader` logic as the worker,
but linked directly into the launcher rather than loaded via classloader isolation. The
code can be shared as source (or a small shared library module) between the launcher and
the worker.

### 7.2 `resolveJavaHome()`

When Maven backend is active, JVM download is **disabled**. The launcher checks:

```scala
def resolveJavaHome(
    id: String,
    jvmIndexVersionOpt: Option[String],
    outMode: OutFolderMode,
    millRepositories: Seq[String],
    resolverBackend: String
): os.Path = {
  if (resolverBackend == "maven") {
    // Maven backend requires system JVM
    val javaHome = Option(System.getenv("JAVA_HOME"))
      .orElse(Option(System.getProperty("java.home")))
      .getOrElse(sys.error(
        "Maven resolver backend requires a system-installed JVM. " +
        "Set JAVA_HOME or use mill-jvm-version: system"
      ))
    os.Path(javaHome)
  } else {
    // existing Coursier JVM resolution
    resolveJavaHomeCoursier(id, jvmIndexVersionOpt, outMode, millRepositories)
  }
}
```

---

## 8. Type Conversion Reference

### 8.1 Coursier `Dependency` → `DependencyCoord`

| Coursier field | Worker API field |
|---------------|-----------------|
| `dep.module.organization.value` | `groupId` |
| `dep.module.name.value` | `artifactId` |
| `dep.versionConstraint.asString` | `version` |
| `dep.configuration.value` | `configuration` |
| `dep.minimizedExclusions` | `exclusions` |
| `dep.attributes.classifier.value` | `classifier` |
| `dep.attributes.type.value` | `type` |

### 8.2 Coursier `Repository` → `RepoCoord`

Only `MavenRepository` instances are convertible. Ivy repositories are not supported
by Maven Resolver. When an Ivy repository is encountered with the Maven backend:
- Log a warning: "Ivy repository {url} ignored — Maven resolver backend only supports Maven repositories"
- Skip it

### 8.3 Worker `ResolvedArtifact` → Coursier tuple

| Worker field | Coursier target |
|-------------|----------------|
| `groupId` + `artifactId` | `coursier.Dependency.module` |
| `version` | `coursier.Dependency.versionConstraint` |
| `classifier` | `coursier.core.Publication.classifier` |
| `path` | `java.io.File` |

### 8.4 Worker `ResolutionMetadata` → Coursier `Resolution`

| Worker field | Coursier `Resolution` field |
|-------------|---------------------------|
| `transitiveDeps` | `finalDependenciesCache` |
| `projectCache` → `overrides` | `projectCache` → `Project.overrides` |
| `projectCache` → `licenses` | `projectCache` → `Project.info.licenseInfo` |
| `projectCache` → `parent` | `projectCache` → `Project.parent0` |
| `orderedDeps` | `orderedDependencies` |
| `orderedDeps.toSet` | `dependencies` |

---

## 9. Files Changed / Added

### New files

| File | Purpose |
|------|---------|
| `libs/javalib/maven-resolver-worker/package.mill` | Module definition with Maven Resolver deps |
| `libs/javalib/maven-resolver-worker/src/.../MavenResolverWorkerImpl.scala` | Worker implementation (implements `Api`) |
| `libs/javalib/maven-resolver-worker/src/.../MavenResolverEngine.scala` | Maven Resolver session/resolution logic |
| `libs/javalib/maven-resolver-worker/src/.../SettingsXmlReader.scala` | `settings.xml` parsing |
| `libs/javalib/src/mill/javalib/internal/MavenResolverSupport.scala` | Worker API trait with serializable types |
| `libs/javalib/src/mill/javalib/MavenResolverSupport.scala` | Worker classloader loading trait |
| `libs/util/java11/src/mill/util/CoursierTypeAdapter.scala` | Converts worker results → Coursier types |
| `libs/util/java11/src/mill/util/ResolverBackend.scala` | Backend selection singleton |

### Modified files

| File | Change |
|------|--------|
| `core/constants/src/mill/constants/ConfigConstants.java` | Add `millResolverBackend` constant |
| `libs/util/java11/src/mill/util/Jvm.scala` | Backend switch in 3 methods, Maven path implementations |
| `libs/util/java11/src/mill/util/CoursierConfig.scala` | Add `mavenResolverWorker` field (or separate config) |
| `libs/javalib/src/mill/javalib/CoursierModule.scala` | Mix in `MavenResolverSupport`, pass worker to resolver |
| `libs/javalib/src/mill/javalib/Lib.scala` | Thread worker through to `Jvm.scala` |
| `runner/launcher/src/mill/launcher/MillProcessLauncher.scala` | Read `mill-resolver-backend` config |
| `runner/launcher/src/mill/launcher/CoursierClient.scala` | Backend switch for daemon resolution + JVM guard |
| `runner/launcher/package.mill` | Add Maven Resolver dependencies |
| `core/api/src/mill/api/internal/Cached.scala` | Add `millResolverBackend` field |
| `core/api/src/mill/api/SelectiveExecution.scala` | Add `millResolverBackend` field |
| `core/exec/src/mill/exec/GroupExecution.scala` | Store/check `millResolverBackend` |
| `core/eval/src/mill/eval/SelectiveExecutionImpl.scala` | Invalidation check |
| `libs/javalib/package.mill` | Register `maven-resolver-worker` module |

### Unchanged (verified)

All 40+ consumer modules, all publishing paths, `Dep.scala`, `BoundDep`, all contrib
modules except `CycloneDXJavaModule` which works via the `Resolution` adapter.

---

## 10. Incremental Delivery Plan

Phase 1 can be landed in stages, each independently testable:

### Stage 1: Configuration plumbing + skeleton
1. Add `ConfigConstants.millResolverBackend`
2. Read it in `MillProcessLauncher`
3. Create `ResolverBackend` object
4. Add cache invalidation fields to `Cached` / `SelectiveExecution.Metadata`
5. Add `MavenResolverSupport` API trait (types only, no implementation)
6. Create empty `maven-resolver-worker` module

**Testable:** Build compiles, config is read, `resolverBackend` defaults to `coursier`,
all existing tests pass.

### Stage 2: `resolveClasspath` (covers 90 call sites)
1. Implement `MavenResolverEngine` with session/resolution
2. Implement `SettingsXmlReader`
3. Implement `resolveClasspath` in worker
4. Add backend switch to `Jvm.resolveDependencies`
5. Thread worker through `CoursierConfig` → `Lib` → `Jvm`

**Testable:** `./mill -Dmill.resolver.backend=maven scalalib.compile` resolves all
dependencies via Maven Resolver. Run `example.javalib.basic._` with both backends.

### Stage 3: `resolveArtifacts` (covers 4 call sites)
1. Implement `resolveArtifacts` in worker
2. Add `CoursierTypeAdapter` for artifact tuple construction
3. Add backend switch to `Jvm.fetchArtifacts` and `Jvm.getArtifacts`

**Testable:** Quarkus and NativeImage modules work with Maven backend.

### Stage 4: `resolveMetadata` (covers 3+1 call sites)
1. Implement `resolveMetadata` in worker with transitiveDeps, projectCache
2. Add `CoursierTypeAdapter` for `Resolution` construction
3. Add backend switch to `Jvm.resolveDependenciesMetadataSafe`

**Testable:** `publishLocal` with Maven backend generates correct POM/ivy.xml.
BSP integration works. `CycloneDXJavaModule` SBOM generation works.

### Stage 5: Launcher guard + documentation
1. Add JVM resolution guard in `CoursierClient.resolveJavaHome`
2. Documentation page for `mill-resolver-backend` configuration
3. Integration test: full build with `mill-resolver-backend: maven`

---

## 11. Testing Strategy

### Unit tests

| Test | What it covers |
|------|---------------|
| `CoursierTypeAdapterTests` | Round-trip: Coursier dep → DependencyCoord → resolve → ResolvedArtifact → Coursier tuple |
| `MavenResolverEngineTests` | Direct Maven Resolver calls against local repo or test server |
| `SettingsXmlReaderTests` | Parse various `settings.xml` configurations |
| `ResolverBackendTests` | Configuration reading and propagation |

### Integration tests

| Test | What it covers |
|------|---------------|
| `example.javalib.basic._` with both backends | Basic Java project resolution |
| `example.scalalib.basic._` with both backends | Scala cross-version resolution |
| `integration.feature.publish-local` with Maven backend | POM/ivy.xml generation from Maven resolution |
| New: `integration.feature.maven-resolver` | Backend-specific scenarios: settings.xml, mirrors, auth |

### Test infrastructure

Add test helper to run example/integration tests with Maven backend:
```scala
// In test infrastructure
def withResolverBackend(backend: String)(test: => Unit): Unit = {
  val prev = sys.props.get("mill.resolver.backend")
  sys.props("mill.resolver.backend") = backend
  try test
  finally prev match {
    case Some(v) => sys.props("mill.resolver.backend") = v
    case None => sys.props.remove("mill.resolver.backend")
  }
}
```

### CI strategy

- Default CI runs with `coursier` backend (no change)
- Add a CI job that runs a subset of tests with `maven` backend
- Initially: `example.javalib.basic._` + `example.scalalib.basic._` + `integration.feature.publish-local`
- Expand coverage as confidence grows

---

## 12. Risk Analysis

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Maven Resolver version conflicts with existing `maven-worker` | Medium | Medium | Separate classloaders; each worker has its own deps |
| `Resolution` construction misses a field some caller needs | Medium | Low | We've enumerated all callers; add fields incrementally |
| Maven Resolver slower for large dependency graphs | Low | Medium | Benchmark early; Maven Resolver 2.x has improved perf |
| `settings.xml` edge cases (encrypted passwords, custom profiles) | Medium | Low | Start with basic support; document limitations |
| Ivy repository configurations silently ignored | Low | Low | Log warning when Ivy repos are skipped |
| BOM handling differences between Coursier and Maven Resolver | Medium | Medium | Maven Resolver is the reference impl; test with real BOMs |
| Worker classloader fails to load on some JVM versions | Low | High | Test on JDK 11, 17, 21; use standard classloader API |

### Known limitations in Phase 1

1. **No Gradle module metadata support** — `checkGradleModules` is ignored with Maven backend
2. **No Ivy repository support** — Maven Resolver only handles Maven repos
3. **Coursier types on classpath** — not a security issue, just not fully clean
4. **No JVM download** — requires system JVM when Maven backend is active
5. **No encrypted password support in settings.xml** — plain-text or environment variable credentials only

---

## 13. Open Decisions

### Decision 1: Meta-build resolution

**Question:** Does `mill-build/` (the build that builds the build) use the Maven backend?

**Recommendation:** No. The meta-build resolves Mill plugins and build dependencies,
which is distinct from the user's project dependencies. The configuration applies only
to the user build. This keeps meta-build resolution stable and avoids circular
bootstrapping issues.

### Decision 2: Where does `CoursierTypeAdapter` live?

**Options:**
- `libs/util/java11/` — alongside `Jvm.scala` (minimal new dependencies)
- `libs/javalib/` — alongside `CoursierModule` (closer to consumer)

**Recommendation:** `libs/util/java11/` — it only depends on Coursier types (already
on classpath) and plain Scala types from the worker API.

### Decision 3: Worker API types — shared module or inline?

**Options:**
- Separate `mill-resolver-api` module (clean, but adds a module)
- Define types in `libs/javalib/internal/` (simpler, follows `MavenWorkerSupport` pattern)

**Recommendation:** `libs/javalib/internal/` for Phase 1, matching the existing pattern.
Phase 2 can extract to a dedicated module.

### Decision 4: Error message format

**Question:** Should Maven Resolver errors match Coursier's error format?

**Recommendation:** No. Provide clear Maven-style errors with repository URLs, HTTP
status codes, and POM paths. Users choosing Maven backend expect Maven-style diagnostics.
Include a note: "Resolution backend: maven (Maven Resolver)" in error output so users
know which backend produced the error.

---

## Appendix A: Dependency Graph

```
User build.mill
  └─ CoursierModule (trait)
       ├─ millResolver() → Resolver
       │   ├─ .classpath()  ──→ Lib.resolveDependencies() ──→ Jvm.resolveDependencies()
       │   ├─ .resolution() ──→ Lib.resolveDependenciesMetadataSafe() ──→ Jvm.resolveDependenciesMetadataSafe()
       │   ├─ .artifacts()  ──→ Jvm.getArtifacts() ──→ Jvm.fetchArtifacts()
       │   └─ .fetchArtifacts() ──→ Jvm.fetchArtifacts()
       │
       └─ MavenResolverSupport (trait, new)
            └─ mavenResolverWorker → loads MavenResolverWorkerImpl
                 └─ resolveClasspath / resolveArtifacts / resolveMetadata
                      └─ MavenResolverEngine (RepositorySystem, settings.xml)

Jvm.scala (backend switch point):
  if (ResolverBackend.isMaven)
    → convert Coursier types → call worker API → convert back to Coursier types
  else
    → existing Coursier code (unchanged)
```

## Appendix B: settings.xml Features Supported in Phase 1

| Feature | Supported | Notes |
|---------|-----------|-------|
| `<localRepository>` | Yes | Override local repo path |
| `<mirrors>` | Yes | Repository mirroring |
| `<servers>` (username/password) | Yes | Plain-text credentials |
| `<servers>` (encrypted passwords) | No | Phase 2; document limitation |
| `<proxies>` | Yes | HTTP/HTTPS proxy config |
| `<profiles>` with `<repositories>` | Yes | Additional repositories |
| `<profiles>` with `<properties>` | Partial | System properties only |
| `<activeProfiles>` | Yes | Profile activation |
| Profile activation by JDK/OS | No | Phase 2 |
