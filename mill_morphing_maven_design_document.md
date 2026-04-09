# Mill Morphing Maven: Hybrid Development Companion

**Date:** 2026-04-09
**Status:** Proposal
**Prerequisite:** [design_phase1_detailed.md](design_phase1_detailed.md) (Maven Resolver backend)
**Scope:** Use Mill as a transparent development accelerator alongside an existing Maven build

---

## 1. Problem Statement

Large Maven projects suffer from a painful inner development loop. A developer editing
a single module in a 50-module Maven project faces:

- **Cold JVM startup** on every `mvn compile` invocation
- **No incremental compilation** — `maven-compiler-plugin` recompiles broadly, Zinc-level
  per-file tracking does not exist
- **No watch mode** — developers must manually re-run `mvn` after every save
- **No task caching** — unchanged modules are re-processed on every invocation
- **Plugin overhead** — every plugin in the lifecycle runs, even when only compilation
  output is needed

Meanwhile, the Maven build itself is deeply valuable and cannot be replaced:

- Dozens of plugins handle code generation, packaging, deployment, reporting, enforcement
- CI/CD pipelines are built around Maven
- Team workflows, IDE integration, and release processes assume Maven
- Plugin configurations represent years of accumulated institutional knowledge

**The gap:** Maven owns the build, but the developer experience during active coding is
poor. Mill has excellent dev ergonomics (Zinc incremental compilation, file watching,
task caching, warm daemon process) but cannot replicate the full Maven plugin ecosystem.

**The proposal:** Mill acts as a transparent development companion. It reads the live
Maven POM, resolves dependencies through Maven's own resolver, and provides fast
incremental compilation with watch mode for the modules the developer is actively editing.
The Maven build remains the source of truth. No changes to `pom.xml` are required.
Teammates do not need to know Mill exists.

---

## 2. Design Principles

1. **Zero Maven changes** — the Maven build is untouched. No new profiles, no plugin
   additions, no POM modifications. Mill reads the POM as-is.

2. **POM is the source of truth** — Mill derives its module configuration from
   `pom.xml` at build time, every time. There is no one-shot code generation or
   generated `build.mill` that drifts from the POM. When the POM changes, Mill
   picks it up automatically.

3. **Surgical scope** — Mill only compiles the module(s) the developer is actively
   working on. Sibling modules are consumed as pre-built artifacts from
   `~/.m2/repository`. This keeps Mill's scope minimal and startup fast.

4. **Maven Resolver fidelity** — dependency resolution uses the Phase 1 Maven Resolver
   backend, ensuring identical version selection, BOM handling, mirror routing, and
   credential management as the Maven build itself.

5. **Graceful degradation** — when Mill cannot handle a build step (e.g., a Maven plugin
   that generates sources), it delegates to `mvn` as a subprocess. The developer
   gets Mill speed for compilation and falls back to Maven for everything else.

6. **Opt-in, per-module** — the developer chooses which module(s) to accelerate. The
   rest of the project is invisible to Mill.

---

## 3. Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                 Developer Workflow                    │
│                                                      │
│  mvn install -DskipTests    (one-time, full build)   │
│  ./mill -w myModule.compile (inner loop, fast)       │
│  mvn verify                 (pre-commit, full build) │
└─────────────────────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌─────────────┐ ┌──────────────┐ ┌──────────────────┐
│  pom.xml    │ │ ~/.m2/repo   │ │ settings.xml     │
│  (watched)  │ │ (sibling     │ │ (mirrors, creds, │
│             │ │  artifacts)  │ │  proxies)        │
└──────┬──────┘ └──────┬───────┘ └────────┬─────────┘
       │               │                  │
       ▼               ▼                  ▼
┌──────────────────────────────────────────────────────┐
│              MavenCompanionModule                     │
│                                                       │
│  pomModel ← live parse of pom.xml via Modeler         │
│  mvnDeps ← derived from <dependencies>                │
│  javacOptions ← derived from maven-compiler-plugin    │
│  siblingClasspath ← JARs from ~/.m2/repository        │
│  generatedSources ← delegate to mvn generate-sources  │
│                                                       │
│  compile → Zinc incremental compilation (fast)        │
│  test → JUnit/TestNG via Mill's test runner           │
│  run → forked JVM with full classpath                 │
└───────────────────────────────────────────────────────┘
```

---

## 4. Core Module: `MavenCompanionModule`

### 4.1 Trait Definition

A new trait in `libs/javalib/` that extends `MavenModule` with live POM reading:

```scala
package mill.javalib

import mill.*
import org.apache.maven.model.Model

/**
 * A module that reads its configuration from a live Maven pom.xml,
 * enabling Mill's incremental compilation, caching, and watch mode
 * alongside an existing Maven build.
 *
 * The Maven build remains the source of truth. Mill reads pom.xml
 * at build time and derives dependencies, compiler options, and
 * source directories from it.
 */
trait MavenCompanionModule extends MavenModule { outer =>

  /**
   * Path to the Maven project root. Defaults to this module's directory.
   * Override when the Mill module tree doesn't mirror the Maven layout.
   */
  def mavenProjectRoot: T[os.Path] = Task { millSourcePath }

  /**
   * The pom.xml file. Declared as Task.Source so Mill watches it
   * for changes and re-derives all configuration when it's modified.
   */
  def pomFile: T[PathRef] = Task.Source(mavenProjectRoot() / "pom.xml")

  /**
   * The effective Maven model, fully resolved with parent inheritance,
   * property interpolation, and profile activation.
   */
  def pomModel: T[Model] = Task {
    val modeler = Modeler(mavenProjectRoot())
    modeler.build((mavenProjectRoot() / "pom.xml").toIO).getEffectiveModel
  }

  // --- Dependency derivation ---

  override def mvnDeps: T[Seq[Dep]] = Task {
    PomDependencyExtractor.compileDeps(pomModel())
  }

  override def compileMvnDeps: T[Seq[Dep]] = Task {
    PomDependencyExtractor.providedDeps(pomModel())
  }

  override def runMvnDeps: T[Seq[Dep]] = Task {
    PomDependencyExtractor.runtimeDeps(pomModel())
  }

  override def bomMvnDeps: T[Seq[Dep]] = Task {
    PomDependencyExtractor.bomDeps(pomModel())
  }

  override def depManagement: T[Seq[Dep]] = Task {
    PomDependencyExtractor.depManagement(pomModel())
  }

  // --- Compiler options derivation ---

  override def javacOptions: T[Seq[String]] = Task {
    PomPluginExtractor.javacOptions(pomModel())
  }

  override def annotationProcessorsMvnDeps: T[Seq[Dep]] = Task {
    PomPluginExtractor.annotationProcessorDeps(pomModel())
  }

  // --- Sibling module artifacts from ~/.m2/repository ---

  /**
   * Pre-built JARs for sibling Maven modules, resolved from the local
   * Maven repository. These modules are NOT compiled by Mill — they are
   * consumed as artifacts from a prior `mvn install`.
   */
  def siblingArtifacts: T[Seq[PathRef]] = Task {
    val model = pomModel()
    val localRepo = MavenLocalRepo.path(model)
    PomDependencyExtractor.siblingModuleDeps(model).flatMap { dep =>
      val jar = localRepo /
        os.RelPath(dep.groupId.replace('.', '/')) /
        dep.artifactId / dep.version /
        s"${dep.artifactId}-${dep.version}.jar"
      if (os.exists(jar)) Some(PathRef(jar))
      else None
    }
  }

  override def unmanagedClasspath: T[Seq[PathRef]] = Task {
    siblingArtifacts()
  }

  // --- Maven plugin delegation ---

  /**
   * Whether this module requires Maven-generated sources (e.g., protobuf,
   * JAXB, annotation processing via Maven-specific plugins).
   */
  def mavenGenerateSourcesEnabled: T[Boolean] = Task { false }

  /**
   * Specific Maven goals to run for source generation.
   * Default: `generate-sources`. Override for e.g. `generate-test-sources`.
   */
  def mavenGenerateGoals: T[Seq[String]] = Task { Seq("generate-sources") }

  /**
   * Delegates source generation to Maven via subprocess, returning
   * the path to generated sources that feed into Mill's compilation.
   */
  def mavenGeneratedSources: T[Seq[PathRef]] = Task {
    if (!mavenGenerateSourcesEnabled()) Seq.empty
    else {
      val goals = mavenGenerateGoals()
      val artifactId = pomModel().getArtifactId
      os.proc(
        "mvn", goals, "-pl", s":$artifactId", "-N",
        "-q", "-DskipTests", "-Dmaven.javadoc.skip=true"
      ).call(cwd = mavenProjectRoot(), check = true)

      // Collect generated source directories from target/
      val target = mavenProjectRoot() / "target"
      val genDirs = Seq(
        target / "generated-sources",
        target / "generated-test-sources"
      ).filter(os.exists)
      genDirs.flatMap(os.list(_)).filter(os.isDir).map(PathRef(_))
    }
  }

  override def generatedSources: T[Seq[PathRef]] = Task {
    super.generatedSources() ++ mavenGeneratedSources()
  }

  // --- Test companion ---

  trait MavenCompanionTests extends MavenTests with MavenCompanionModule {
    override def mavenProjectRoot = outer.mavenProjectRoot

    override def mvnDeps: T[Seq[Dep]] = Task {
      PomDependencyExtractor.testDeps(outer.pomModel())
    }

    override def testFramework: T[String] = Task {
      PomTestFrameworkDetector.detect(outer.pomModel())
    }
  }
}
```

### 4.2 Why `Task` and not one-shot code generation

The critical difference from `mill init` is that `pomModel` is a **Mill Task**, not a
code generation step. This means:

1. **`pom.xml` is watched** — `pomFile` is a `Task.Source`, so Mill detects POM
   changes and re-derives everything automatically.

2. **No generated `build.mill` to maintain** — there's no drift between the POM and
   the Mill configuration. The POM IS the configuration.

3. **Property changes propagate immediately** — if a developer bumps a dependency
   version in the POM, Mill picks it up on the next compilation cycle.

4. **No merge conflicts** — there's no generated file that conflicts with POM changes.

### 4.3 Relationship to existing `mill init`

`mill init` (in `libs/init/maven/`) does a one-shot conversion: it reads the POM and
generates `build.mill` / `build.mill.yaml` files. This is useful for permanent migration
from Maven to Mill.

`MavenCompanionModule` is the opposite use case: you are NOT migrating. You want Mill's
dev ergonomics while keeping Maven as your build system. The POM parsing machinery is
shared (both use `Modeler` from `libs/init/maven/`), but the lifecycle is different.

Both can coexist. A team might start with `MavenCompanionModule` for dev acceleration,
and later decide to `mill init` for a full migration.

---

## 5. POM Parsing Infrastructure

### 5.1 Reusing `Modeler`

The existing `Modeler` class (`libs/init/maven/src/mill/main/maven/Modeler.scala`)
already provides full POM parsing:

- Two-phase model building via Apache Maven's `DefaultModelBuilder`
- Parent POM resolution via Aether's `RepositorySystem`
- Property interpolation (`${project.version}`, `${some.property}`)
- Profile activation
- Dependency management inheritance

Currently, `Modeler` lives in the `libs/init/maven` module which is only available
at init time. For `MavenCompanionModule`, we need it available at build time.

**Approach:** Extract the POM parsing core into a shared module that both `libs/init/maven`
and `libs/javalib` can depend on.

### 5.2 New module: `maven-pom-reader`

```
libs/javalib/maven-pom-reader/
  package.mill
  src/mill/javalib/maven/pom/
    PomReader.scala            — wraps Modeler for build-time use
    PomDependencyExtractor.scala — extracts deps by scope
    PomPluginExtractor.scala     — extracts compiler opts, annotation processors
    PomTestFrameworkDetector.scala — detects JUnit4/5, TestNG from deps
    MavenLocalRepo.scala         — locates JARs in ~/.m2/repository
```

This module follows the same worker classloader pattern as `maven-resolver-worker`
and the existing `maven-worker`. Maven's `ModelBuilder` and its transitive dependencies
(Plexus, SIS, etc.) are loaded in an isolated classloader to avoid conflicts with Mill's
own classpath.

### 5.3 `PomDependencyExtractor`

Converts Maven `Model` dependencies to Mill `Dep` objects:

```scala
package mill.javalib.maven.pom

import mill.javalib.Dep
import org.apache.maven.model.{Dependency, Model}
import scala.jdk.CollectionConverters.*

object PomDependencyExtractor {

  /** Dependencies with compile scope (default) → JavaModule.mvnDeps */
  def compileDeps(model: Model): Seq[Dep] =
    model.getDependencies.asScala.toSeq
      .filter(d => d.getScope == null || d.getScope == "compile")
      .filterNot(isModuleDep(model, _))
      .map(toDep)

  /** Dependencies with provided scope → JavaModule.compileMvnDeps */
  def providedDeps(model: Model): Seq[Dep] =
    model.getDependencies.asScala.toSeq
      .filter(_.getScope == "provided")
      .map(toDep)

  /** Dependencies with runtime scope → JavaModule.runMvnDeps */
  def runtimeDeps(model: Model): Seq[Dep] =
    model.getDependencies.asScala.toSeq
      .filter(_.getScope == "runtime")
      .map(toDep)

  /** Dependencies with test scope → test module's mvnDeps */
  def testDeps(model: Model): Seq[Dep] =
    model.getDependencies.asScala.toSeq
      .filter(_.getScope == "test")
      .map(toDep)

  /** BOM imports from dependencyManagement */
  def bomDeps(model: Model): Seq[Dep] =
    Option(model.getDependencyManagement).toSeq
      .flatMap(_.getDependencies.asScala)
      .filter(d => d.getScope == "import" && d.getType == "pom")
      .map(toDep)

  /** Dependency management entries (version pinning) */
  def depManagement(model: Model): Seq[Dep] =
    Option(model.getDependencyManagement).toSeq
      .flatMap(_.getDependencies.asScala)
      .filterNot(d => d.getScope == "import" && d.getType == "pom")
      .map(toDep)

  /** Intra-project module dependencies (identified by groupId match) */
  def siblingModuleDeps(model: Model): Seq[SiblingDep] =
    model.getDependencies.asScala.toSeq
      .filter(isModuleDep(model, _))
      .map(d => SiblingDep(d.getGroupId, d.getArtifactId, d.getVersion))

  case class SiblingDep(groupId: String, artifactId: String, version: String)

  private def toDep(d: Dependency): Dep = {
    val base = s"${d.getGroupId}:${d.getArtifactId}:${d.getVersion}"
    Dep.parse(base)
  }

  private def isModuleDep(model: Model, dep: Dependency): Boolean =
    dep.getGroupId == model.getGroupId &&
      dep.getVersion == model.getVersion
}
```

### 5.4 `PomPluginExtractor`

Extracts compiler options from Maven plugin configurations. This reuses the logic from
the existing `Plugins` class (`libs/init/maven/src/mill/main/maven/Plugins.scala`) but
returns Mill-native types instead of `ModuleSpec` types:

```scala
object PomPluginExtractor {

  /** Extract javac options from maven-compiler-plugin configuration */
  def javacOptions(model: Model): Seq[String] = {
    val plugins = new Plugins(model)
    plugins.javacOptions.flatMap {
      case Opt(key, value) => Seq(key, value)
      case Opt(flag) => Seq(flag)
    }
  }

  /** Extract annotation processor dependencies */
  def annotationProcessorDeps(model: Model): Seq[Dep] = {
    val plugins = new Plugins(model)
    plugins.errorProneMvnDeps.map(d =>
      Dep.parse(s"${d.organization}:${d.name}:${d.version}")
    )
  }
}
```

### 5.5 `PomTestFrameworkDetector`

Detects the test framework by inspecting test-scope dependencies, matching the logic
already in `MillMavenBuildGenMain`:

```scala
object PomTestFrameworkDetector {

  def detect(model: Model): String = {
    val testDeps = model.getDependencies.asScala.toSeq
      .filter(_.getScope == "test")
      .map(d => s"${d.getGroupId}:${d.getArtifactId}")
      .toSet

    if (testDeps.exists(_.contains("junit-jupiter")))
      "com.github.sbt.junit.jupiter.api.JUnit5"
    else if (testDeps.exists(_.contains("testng")))
      "mill.testng.TestNGFramework"
    else if (testDeps.exists(_.contains("junit")))
      "com.novocode.junit.JUnitFramework"
    else
      "com.novocode.junit.JUnitFramework" // safe default
  }
}
```

### 5.6 `MavenLocalRepo`

Locates artifacts in the local Maven repository:

```scala
object MavenLocalRepo {

  /** Path to the local Maven repository, respecting settings.xml overrides */
  def path(model: Model): os.Path = {
    // Check settings.xml for <localRepository> override
    val settingsLocal = Option(System.getProperty("maven.repo.local"))
      .orElse(readLocalRepoFromSettings())
    settingsLocal.map(os.Path(_)).getOrElse(os.home / ".m2" / "repository")
  }

  /** Resolve a specific artifact JAR from the local repo */
  def resolveJar(
      localRepo: os.Path,
      groupId: String,
      artifactId: String,
      version: String,
      classifier: String = ""
  ): Option[os.Path] = {
    val classifierSuffix = if (classifier.isEmpty) "" else s"-$classifier"
    val jar = localRepo /
      os.RelPath(groupId.replace('.', '/')) /
      artifactId / version /
      s"$artifactId-$version$classifierSuffix.jar"
    Option.when(os.exists(jar))(jar)
  }
}
```

---

## 6. Sibling Module Resolution

### 6.1 The Problem

In a multi-module Maven project:

```
parent/
  pom.xml                    ← parent POM
  module-a/pom.xml           ← library
  module-b/pom.xml           ← depends on module-a
  module-c/pom.xml           ← depends on module-b
```

If the developer is working on `module-b`, they need `module-a` on the classpath.
Compiling `module-a` with Mill defeats the purpose — it adds complexity and means
Mill must understand module-a's full build (which may have its own Maven plugins).

### 6.2 The Solution: Pre-built Artifacts

After `mvn install`, every module's JAR is in `~/.m2/repository`. Mill consumes
sibling modules from there via `unmanagedClasspath`:

```scala
override def unmanagedClasspath: T[Seq[PathRef]] = Task {
  siblingArtifacts()
}
```

The `siblingArtifacts` task:
1. Reads `<dependencies>` from the POM
2. Identifies which dependencies are sibling modules (same `groupId` and `version`)
3. Resolves each to a JAR path in `~/.m2/repository`
4. Returns them as `PathRef` entries (which Mill watches for changes)

### 6.3 Staleness Detection

A key UX concern: the developer changes `module-a` but forgets to `mvn install` it.
Mill would compile `module-b` against a stale `module-a` JAR.

**Detection strategy:**

```scala
/**
 * Check if any sibling module's source tree is newer than its
 * installed JAR. Emit a warning if so.
 */
def staleSiblingCheck: T[Unit] = Task {
  val model = pomModel()
  val localRepo = MavenLocalRepo.path(model)
  val projectRoot = mavenProjectRoot()

  PomDependencyExtractor.siblingModuleDeps(model).foreach { dep =>
    val jar = localRepo /
      os.RelPath(dep.groupId.replace('.', '/')) /
      dep.artifactId / dep.version /
      s"${dep.artifactId}-${dep.version}.jar"

    if (os.exists(jar)) {
      val siblingDir = projectRoot / os.up / dep.artifactId
      if (os.exists(siblingDir / "src")) {
        val jarMtime = os.mtime(jar)
        val newestSource = os.walk(siblingDir / "src")
          .filter(os.isFile)
          .map(os.mtime)
          .maxOption
          .getOrElse(0L)

        if (newestSource > jarMtime) {
          Task.log.error(
            s"WARNING: ${dep.artifactId} sources are newer than installed JAR. " +
            s"Run: mvn install -pl :${dep.artifactId} -DskipTests"
          )
        }
      }
    }
  }
}
```

This check runs as part of `compile` and warns the developer without blocking.

### 6.4 Multi-Module Companion Mode

For developers working on multiple modules simultaneously, a `build.mill` can declare
several companion modules:

```scala
// build.mill for hybrid development
import mill.javalib.*

object `module-a` extends MavenCompanionModule
object `module-b` extends MavenCompanionModule {
  override def moduleDeps = Seq(`module-a`)
}
```

When `module-a` is also a `MavenCompanionModule` (not just a sibling artifact), Mill
compiles it directly and wires it as a `moduleDep`. This gives full incremental
compilation across both modules.

The developer chooses the boundary: modules they're actively editing become
`MavenCompanionModule` instances with Mill compilation; everything else stays as
pre-built artifacts from `~/.m2/repository`.

---

## 7. Maven Plugin Delegation

### 7.1 Taxonomy of Maven Plugins

Not all Maven plugins matter for the inner development loop. We classify them:

| Category | Examples | Mill Handling |
|----------|----------|---------------|
| **Compilation** | `maven-compiler-plugin` | Replaced by Zinc (faster) |
| **Code Generation** | `protobuf-maven-plugin`, `jaxb2-maven-plugin`, `openapi-generator` | Delegate to `mvn generate-sources` |
| **Annotation Processing** | Lombok, MapStruct, Dagger | Mill's native `-processorpath` support |
| **Testing** | `maven-surefire-plugin`, `maven-failsafe-plugin` | Replaced by Mill's test runner |
| **Packaging** | `maven-shade-plugin`, `maven-assembly-plugin`, `spring-boot-maven-plugin` | Not needed in dev loop; use `mvn package` |
| **Deployment** | `maven-deploy-plugin`, `maven-release-plugin` | Not needed in dev loop; use `mvn deploy` |
| **Quality** | `maven-checkstyle-plugin`, `spotbugs-maven-plugin`, `maven-pmd-plugin` | Not needed in dev loop; use `mvn verify` |
| **Enforcement** | `maven-enforcer-plugin` | Not needed in dev loop |
| **Resource Processing** | `maven-resources-plugin` (filtering) | Partial support (see 7.3) |

### 7.2 Code Generation Delegation

For modules that depend on Maven plugins for code generation, the
`mavenGenerateSourcesEnabled` flag triggers delegation:

```scala
object myModule extends MavenCompanionModule {
  override def mavenGenerateSourcesEnabled = Task { true }
}
```

This calls `mvn generate-sources -pl :myModule -N` as a subprocess and adds the
`target/generated-sources/` directory to Mill's `generatedSources`. The generated
files are then compiled by Zinc alongside hand-written sources.

The subprocess call is a Mill `Task`, so it is cached. It only re-runs when:
- `pom.xml` changes (triggers `pomModel` re-evaluation)
- Source files that feed code generation change (if tracked)
- The developer manually invalidates

### 7.3 Resource Filtering

Maven's `maven-resources-plugin` supports property interpolation in resource files
(e.g., replacing `${project.version}` in `application.properties`). Mill does not
do this natively.

**Approach:** Provide a `mavenResourceFiltering` task that performs basic property
substitution using values from the POM model:

```scala
def mavenFilteredResources: T[Seq[PathRef]] = Task {
  val model = pomModel()
  val props = model.getProperties.asScala.toMap ++
    Map(
      "project.version" -> model.getVersion,
      "project.groupId" -> model.getGroupId,
      "project.artifactId" -> model.getArtifactId
    )

  val resourceDirs = Seq(mavenProjectRoot() / "src" / "main" / "resources")
  val dest = Task.dest / "filtered-resources"
  os.makeDir.all(dest)

  resourceDirs.filter(os.exists).foreach { dir =>
    os.walk(dir).filter(os.isFile).foreach { src =>
      val rel = src.relativeTo(dir)
      val target = dest / rel
      os.makeDir.all(target / os.up)
      if (isFilterable(src)) {
        var content = os.read(src)
        props.foreach { case (k, v) =>
          content = content.replace(s"$${$k}", v)
        }
        os.write.over(target, content)
      } else {
        os.copy.over(src, target)
      }
    }
  }
  Seq(PathRef(dest))
}

private def isFilterable(path: os.Path): Boolean =
  Set("properties", "xml", "yml", "yaml", "json", "txt")
    .contains(path.ext)
```

This covers the common case. Complex filtering (custom delimiters, profile-specific
values) falls back to `mvn process-resources`.

---

## 8. Phase 1 Maven Resolver Integration

### 8.1 Why Phase 1 is Required

The `MavenCompanionModule` depends on the Phase 1 Maven Resolver backend
(`design_phase1_detailed.md`) for dependency resolution fidelity. Without it:

| Scenario | Coursier | Maven Resolver (Phase 1) |
|----------|----------|--------------------------|
| Corporate mirror in `settings.xml` | Not read | Read natively |
| Server credentials in `settings.xml` | Not read | Read natively |
| HTTP/HTTPS proxy in `settings.xml` | Not read | Read natively |
| BOM dependency management | Mostly works, edge cases | Reference implementation |
| `<exclusions>` with wildcards | Supported | Supported |
| SNAPSHOT resolution policies | Different behavior | Matches `mvn` exactly |
| `<repositories>` in POM profiles | Not activated | Profile activation rules apply |

In enterprise environments, items 1-3 are non-negotiable. A developer whose Maven build
goes through a Nexus mirror with authentication cannot use Mill at all without
`settings.xml` support.

### 8.2 Configuration

The companion module activates the Maven resolver backend:

```
# .mill-resolver-backend (or in build.mill header)
maven
```

This single flag routes all dependency resolution through Maven Resolver, reading
`settings.xml` automatically. No additional configuration is needed — the same
mirrors, credentials, and proxies that Maven uses are picked up by Mill.

### 8.3 Local Repository as Cache

Maven Resolver natively checks `~/.m2/repository` before making network requests.
After a `mvn install`, all transitive dependencies of the entire project are already
cached locally. This means Mill's first dependency resolution is essentially instant —
no network traffic, just local file reads.

This is a significant UX advantage: the developer runs `mvn install` once, then Mill
operates fully offline for compilation.

---

## 9. Developer Workflow

### 9.1 Initial Setup

One-time setup to start using Mill alongside an existing Maven project:

```bash
# 1. Install Mill (if not already available)
curl -L https://raw.githubusercontent.com/com-lihaoyi/mill/main/mill -o mill
chmod +x mill

# 2. Create minimal build.mill at project root (or in the module directory)
cat > build.mill << 'EOF'
import mill.javalib.*

object myModule extends MavenCompanionModule
EOF

# 3. Configure Maven resolver backend
echo "maven" > .mill-resolver-backend

# 4. Full Maven build to populate ~/.m2/repository
mvn install -DskipTests

# 5. Verify Mill can compile the module
./mill myModule.compile
```

### 9.2 Daily Development Loop

```bash
# Start watch mode — Mill recompiles on every save
./mill -w myModule.compile

# In another terminal, run tests (also cached/incremental)
./mill myModule.test

# Or run the application
./mill myModule.run

# When changing a sibling module:
mvn install -pl :sibling-module -DskipTests
# Mill detects the updated JAR and recompiles

# Before committing — full Maven verification
mvn verify
```

### 9.3 Multi-Developer Scenario

The `build.mill` file can be `.gitignore`d, making Mill completely invisible to
teammates who don't use it:

```gitignore
# Mill companion (optional dev tool)
build.mill
.mill-resolver-backend
.mill-version
out/
```

Alternatively, the team can commit `build.mill` as an optional development aid
alongside the Maven build. Both builds are valid; developers choose which to use
for their inner loop.

---

## 10. Performance Characteristics

### 10.1 Compilation Speed

| Scenario | Maven | Mill Companion |
|----------|-------|----------------|
| Single file changed, small module | 5-15s | 0.3-1s |
| Single file changed, large module | 15-60s | 1-3s |
| No changes (re-run) | 3-10s (plugin overhead) | 0s (cached) |
| Dependency added to POM | 15-30s | 5-10s (re-resolve + recompile) |
| Clean build of one module | 10-30s | 5-15s |

The speedup comes from:
1. **Zinc incremental compilation** — only recompiles files affected by the change
2. **Warm daemon** — no JVM startup cost after the first run
3. **Task caching** — unchanged tasks return cached results instantly
4. **No plugin overhead** — only compilation runs, not the full Maven lifecycle

### 10.2 Memory Overhead

The Mill daemon process runs alongside Maven (not instead of it). Typical memory:
- Mill daemon: 200-500 MB (depending on module complexity)
- Zinc compiler: 100-300 MB (loaded on first compile)

This is comparable to an IDE's background compilation, which most developers already
run. The Mill daemon can be stopped with `./mill shutdown` when not needed.

### 10.3 Disk Overhead

Mill stores compilation output in the `out/` directory:
- Compiled classes: same size as Maven's `target/classes`
- Zinc analysis: 1-10 MB per module
- Cached task results: minimal

The `out/` directory can be deleted at any time without affecting the Maven build.

---

## 11. Limitations and Non-Goals

### 11.1 What This Does NOT Do

1. **Replace Maven** — the Maven build is the source of truth for CI, releases, and
   deployment. Mill is a development-time accelerator only.

2. **Support all Maven plugins** — only compilation-relevant plugin configurations
   (compiler options, annotation processors) are extracted. Other plugins are either
   delegated to `mvn` or not needed for the dev loop.

3. **Handle Gradle builds** — this design is specific to Maven. A similar approach
   for Gradle would require different parsing infrastructure.

4. **Support reactor builds** — Mill does not replicate Maven's reactor build ordering.
   It compiles only the targeted module(s) and consumes siblings as pre-built artifacts.

5. **Replicate Maven profiles** — profile activation (by JDK version, OS, property)
   is handled by Maven's `ModelBuilder` when parsing the POM. Mill does not add its
   own profile activation logic.

### 11.2 Known Edge Cases

| Edge Case | Behavior |
|-----------|----------|
| SNAPSHOT sibling dependencies | Resolved from `~/.m2/repository`; may be stale. Staleness check warns. |
| Maven Wrapper (`mvnw`) | `mavenGeneratedSources` should use `mvnw` if present. Configurable via `def mavenCommand`. |
| Multi-module with relocated sources | Override `mavenProjectRoot` to point at the correct directory. |
| Encrypted passwords in `settings.xml` | Not supported in Phase 1. Use environment variable credentials. |
| Maven extensions (`extensions.xml`) | Not loaded by Mill. Delegate to Maven for steps that require them. |
| Gradle module metadata | Ignored when using Maven resolver backend. |
| Parent POM from remote repo | Resolved by `Modeler` via Aether, same as Maven does. |

---

## 12. Implementation Plan

### Stage 1: `maven-pom-reader` Worker Module

**Goal:** Make POM parsing available at build time.

1. Create `libs/javalib/maven-pom-reader/` module with classloader isolation
2. Define worker API: `PomReader.Api` with `readEffectiveModel(pomPath: String): Model`
3. Extract `PomDependencyExtractor`, `PomPluginExtractor`, `PomTestFrameworkDetector`
4. Extract `MavenLocalRepo` utility
5. Wire worker loading into `MavenCompanionModule` trait

**Testable:** Parse a real `pom.xml` and verify extracted dependencies match Maven's
resolution.

### Stage 2: Core `MavenCompanionModule` Trait

**Goal:** Compile a single Maven module with Mill.

1. Implement `MavenCompanionModule` trait extending `MavenModule`
2. Implement `pomModel`, `mvnDeps`, `javacOptions` derivation from POM
3. Implement `siblingArtifacts` resolution from `~/.m2/repository`
4. Wire `unmanagedClasspath` to `siblingArtifacts`
5. Implement `staleSiblingCheck`

**Testable:** `./mill -w myModule.compile` works for a single module in a multi-module
Maven project, after `mvn install`.

**Depends on:** Phase 1 Maven Resolver backend for enterprise environments. Can be
developed in parallel using Coursier for initial testing.

### Stage 3: Maven Plugin Delegation

**Goal:** Support modules that depend on Maven code generation.

1. Implement `mavenGeneratedSources` task with subprocess delegation
2. Implement `mavenFilteredResources` for basic resource filtering
3. Add `mavenCommand` configuration (default `mvn`, configurable to `mvnw`)
4. Wire generated sources into `generatedSources`

**Testable:** Module using `protobuf-maven-plugin` compiles successfully via Mill
with generated protobuf sources.

### Stage 4: Multi-Module Companion

**Goal:** Support multiple modules compiled by Mill simultaneously.

1. Support `moduleDeps` between `MavenCompanionModule` instances
2. Auto-detect which siblings are companions vs. pre-built artifacts
3. Transitive dependency resolution across companion modules

**Testable:** Two companion modules with inter-dependency compile incrementally.

### Stage 5: Test Support

**Goal:** Run tests through Mill with automatic framework detection.

1. Implement `MavenCompanionTests` trait
2. Test framework auto-detection from POM dependencies
3. Surefire system property extraction for fork arguments
4. Test resource handling

**Testable:** `./mill myModule.test` runs JUnit 5 tests discovered from POM configuration.

### Stage 6: Scaffolding Command

**Goal:** `mill init --companion` generates a minimal `build.mill` for companion mode.

1. Add `--companion` flag to `mill init`
2. Scan Maven modules and generate `MavenCompanionModule` objects
3. Auto-detect which modules likely need `mavenGenerateSourcesEnabled`
4. Generate `.mill-resolver-backend` file
5. Generate `.gitignore` entries

**Testable:** `mill init --companion` on an existing Maven project produces a working
`build.mill` that can compile the project.

---

## 13. Relationship to Existing Mill Features

### 13.1 `mill init` (Maven Import)

`mill init` performs a permanent, one-shot migration from Maven to Mill. It generates
`build.mill` files that replace the Maven build entirely.

`MavenCompanionModule` is complementary: it reads the live POM without generating any
build files. The Maven build continues to be the source of truth.

A migration path exists:
1. Start with `MavenCompanionModule` for dev acceleration
2. As confidence grows, `mill init` to generate a full Mill build
3. Gradually remove Maven artifacts (POM files, `mvnw`, etc.)

### 13.2 `MavenModule` Trait

`MavenModule` (`libs/javalib/src/mill/javalib/MavenModule.scala`) provides the Maven
directory layout (`src/main/java`, `src/main/resources`). `MavenCompanionModule` extends
it, inheriting the directory conventions.

### 13.3 BSP (Build Server Protocol)

Mill's BSP support works with `MavenCompanionModule` out of the box. IntelliJ IDEA
and VS Code can use Mill as the BSP server for the companion modules, getting fast
incremental compilation and error reporting while the Maven build handles everything else.

This is particularly powerful: the developer opens the project in IntelliJ with Maven
import for the full project, but configures BSP from Mill for the specific module
they're editing. They get IntelliJ's Maven plugin support for navigation/refactoring
across the whole project, plus Mill's fast incremental compilation for their active module.

### 13.4 Selective Execution

Mill's selective execution (`SelectiveExecution`) works naturally with companion modules.
When `pom.xml` changes, all derived tasks (dependencies, compiler options) are invalidated.
When only source files change, only compilation re-runs.

---

## 14. Testing Strategy

### 14.1 Unit Tests

| Test Suite | What It Covers |
|------------|---------------|
| `PomDependencyExtractorTests` | Dependency extraction by scope from various POM structures |
| `PomPluginExtractorTests` | Compiler option extraction from `maven-compiler-plugin` configs |
| `PomTestFrameworkDetectorTests` | Framework detection from dependency combinations |
| `MavenLocalRepoTests` | Artifact path resolution in `~/.m2/repository` |
| `StalenessCheckTests` | Sibling staleness detection logic |

### 14.2 Integration Tests

| Test | What It Covers |
|------|---------------|
| `integration.feature.maven-companion.basic` | Single module companion, compile + test |
| `integration.feature.maven-companion.multi-module` | Multi-module with sibling artifacts |
| `integration.feature.maven-companion.code-generation` | Maven plugin delegation for generated sources |
| `integration.feature.maven-companion.resource-filtering` | Property interpolation in resources |
| `integration.feature.maven-companion.bom` | BOM dependency management |

### 14.3 Test Infrastructure

Create a set of minimal Maven projects in `integration/feature/maven-companion/`:

```
maven-companion/
  resources/
    simple-project/
      pom.xml
      src/main/java/com/example/App.java
    multi-module/
      pom.xml
      module-a/pom.xml, src/...
      module-b/pom.xml, src/...  (depends on module-a)
    with-codegen/
      pom.xml
      src/main/proto/messages.proto
    with-bom/
      pom.xml  (imports spring-boot-dependencies BOM)
      src/...
```

Each test:
1. Runs `mvn install -DskipTests` on the Maven project
2. Copies the project to a sandbox
3. Creates a `build.mill` with `MavenCompanionModule`
4. Runs `./mill myModule.compile` and verifies success
5. Modifies a source file and verifies incremental compilation

---

## 15. Risk Analysis

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| POM parsing performance on large multi-module projects | Medium | Medium | Parse only the targeted module's POM, not the entire reactor. Cache the `Model` as a Mill task. |
| Maven `ModelBuilder` version conflicts with Mill's classpath | Medium | High | Classloader isolation via worker pattern, same as `maven-worker`. |
| Stale sibling artifacts causing subtle compilation errors | High | Medium | Staleness detection with clear warnings. Consider auto-running `mvn install` for stale siblings. |
| `settings.xml` not found or misconfigured | Low | Medium | Clear error messages. Fall back to defaults. Phase 1 handles this. |
| Maven plugin delegation slow for frequent code generation | Medium | Low | Cache generated sources. Only re-run when POM or input files change. |
| Developer confusion between Mill and Maven compilation output | Medium | Low | Mill uses `out/` directory; Maven uses `target/`. No overlap. |
| Annotation processors behave differently under Zinc vs. Maven | Low | High | Test with common processors (Lombok, MapStruct). Document known incompatibilities. |

---

## 16. Open Questions

### Question 1: Where Does `build.mill` Live?

**Options:**
- **Project root** (alongside parent `pom.xml`) — standard Mill convention, but may
  confuse Maven-only developers.
- **Separate directory** (e.g., `.mill/build.mill`) — isolates Mill files, but
  non-standard.
- **Per-module** (e.g., `module-b/build.mill`) — closest to the module being developed,
  but Mill's module discovery expects root-level `build.mill`.

**Recommendation:** Project root, with clear `.gitignore` guidance. This follows Mill
conventions and works with all Mill tooling (BSP, IDE integration, etc.).

### Question 2: Automatic `mvn install` for Stale Siblings?

**Options:**
- **Warn only** — safe, non-invasive, developer decides when to rebuild
- **Auto-install** — convenient but surprising, may interfere with Maven daemon or
  other Maven processes
- **Configurable** — `def autoInstallStaleSiblings: T[Boolean] = Task { false }`

**Recommendation:** Warn only for Phase 1. Auto-install as opt-in later.

### Question 3: Scala Module Support?

Many Maven projects that would benefit from Mill companion mode also use Scala (via
`scala-maven-plugin` or `scalor-maven-plugin`). The `MavenCompanionModule` design
should be extensible to `ScalaModule`:

```scala
trait ScalaMavenCompanionModule extends ScalaModule with MavenCompanionModule {
  override def scalaVersion: T[String] = Task {
    PomPluginExtractor.scalaVersion(pomModel())
  }
}
```

**Recommendation:** Design for it, implement in a later stage.

### Question 4: Relationship to `mill init --keep-maven`?

An alternative to `MavenCompanionModule` is enhancing `mill init` with a mode that
generates `build.mill` but keeps the POM as the source of truth by re-running init
whenever the POM changes.

**Recommendation:** `MavenCompanionModule` is the right approach. Re-running code
generation on POM changes is fragile, requires diffing generated files, and creates
the exact drift problem we're trying to avoid.

---

## Appendix A: Comparison with Other Hybrid Build Approaches

| Approach | How It Works | Tradeoffs |
|----------|-------------|-----------|
| **Mill Companion** (this proposal) | Mill reads POM, compiles with Zinc, delegates to Maven for plugins | Full Maven fidelity. Some setup required. |
| **Maven Daemon (`mvnd`)** | Keeps Maven JVM warm between invocations | Faster startup, but no incremental compilation or watch mode. |
| **Gradle with Maven POM** | Gradle can consume Maven POMs for dependency info | Different build tool; not transparent to Maven users. |
| **IDE background compilation** | IntelliJ/Eclipse compile on save | Limited to the IDE. No CLI. No CI integration. |
| **Zinc standalone** | Run Zinc compiler directly, feed it Maven's classpath | Maximum speed, but no dependency resolution, no task graph. |
| **`mill init` full migration** | Convert Maven to Mill permanently | Best long-term, but high upfront cost and team buy-in needed. |

## Appendix B: Example `build.mill` for a Spring Boot Project

```scala
import mill.javalib.*

// Spring Boot multi-module project companion
// Maven build remains the source of truth

object common extends MavenCompanionModule

object api extends MavenCompanionModule {
  // api depends on common — compiled by Mill
  override def moduleDeps = Seq(common)
}

object service extends MavenCompanionModule {
  // service depends on api (compiled by Mill) and infrastructure (from Maven)
  override def moduleDeps = Seq(api)

  // Uses MapStruct for DTO mapping — delegate to Maven
  override def mavenGenerateSourcesEnabled = Task { true }

  object test extends MavenCompanionTests {
    // JUnit 5 auto-detected from POM
  }
}
```

Developer workflow:

```bash
# Initial setup
mvn install -DskipTests

# Dev loop on service module (+ api and common via moduleDeps)
./mill -w service.compile

# Run tests
./mill service.test

# Full Maven build before committing
mvn verify
```

## Appendix C: Files Changed / Added

### New Files

| File | Purpose |
|------|---------|
| `libs/javalib/maven-pom-reader/package.mill` | Worker module definition with Maven Model deps |
| `libs/javalib/maven-pom-reader/src/.../PomReaderWorkerImpl.scala` | Worker implementation |
| `libs/javalib/maven-pom-reader/src/.../PomDependencyExtractor.scala` | Extracts deps by scope from Maven Model |
| `libs/javalib/maven-pom-reader/src/.../PomPluginExtractor.scala` | Extracts compiler opts from plugin configs |
| `libs/javalib/maven-pom-reader/src/.../PomTestFrameworkDetector.scala` | Detects test framework from deps |
| `libs/javalib/maven-pom-reader/src/.../MavenLocalRepo.scala` | Resolves artifacts in `~/.m2/repository` |
| `libs/javalib/src/mill/javalib/MavenCompanionModule.scala` | Core trait for hybrid development |
| `libs/javalib/src/mill/javalib/internal/PomReaderSupport.scala` | Worker API types |
| `libs/javalib/src/mill/javalib/MavenPomReaderSupport.scala` | Worker classloader loading |
| `integration/feature/maven-companion/src/...` | Integration tests |
| `integration/feature/maven-companion/resources/...` | Test Maven projects |

### Modified Files

| File | Change |
|------|--------|
| `libs/javalib/package.mill` | Register `maven-pom-reader` module |
| `libs/init/maven/src/mill/main/maven/Plugins.scala` | Extract shared plugin parsing logic (may refactor to shared location) |

### Unchanged

All existing Maven integration (`mill init`, `MavenModule`, `maven-worker`), all
existing module types, all consumer modules.
