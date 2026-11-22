# Phase 1.b: Sigstore Verification for Resolved Artifacts

**Date:** 2026-04-09
**Prerequisites:** [design_exploration_report.md](design_exploration_report.md), [design_phase1_detailed.md](design_phase1_detailed.md), [security_report.md](security_report.md)
**Scope:** Post-resolution Sigstore signature verification via contrib module with worker isolation

---

## 1. Motivation

Phase 1.a adds Maven Resolver as a backend, giving Mill proper TLS verification, mirror
enforcement, and configurable checksum policies. What it does **not** provide is
**authenticity verification** -- confirming that an artifact was published by the expected
identity.

Sigstore is an OpenSSF project that provides keyless artifact signing with transparency
logging. Unlike PGP (which requires long-lived keys, key servers, and manual trust
management), Sigstore:

- Ties signatures to verified identities (e.g., a GitHub Actions workflow)
- Uses short-lived certificates (minutes, not years)
- Logs every signature in a public, immutable transparency log (Rekor)
- Eliminates the key distribution problem entirely

**The internal organization use case is the strongest driver.** Inside an organization:

- You control the CI pipeline, so you **know** all your internal artifacts are
  Sigstore-signed by your GitHub/GitLab Actions
- A compromised internal Nexus, a rogue team member publishing from a laptop, or a
  misconfigured CI job would all fail verification
- You don't need the whole world to adopt Sigstore -- just your own artifacts
- As more OSS projects adopt Sigstore on Maven Central, coverage grows automatically

**Current state:** Maven Central accepts `.sigstore.json` bundles alongside artifacts.
The `sigstore-maven-plugin` enables publishers to sign. **No JVM build tool verifies
Sigstore signatures during dependency resolution.** Mill would be the first.

---

## 2. Design Overview

### 2.1 Architecture

```
                                          ┌──────────────────────────┐
                                          │  contrib/sigstore/       │
                                          │  SigstoreVerifyModule    │
                                          │  (user mixes this in)    │
                                          └────────┬─────────────────┘
                                                   │ calls
                                          ┌────────▼─────────────────┐
                                          │  SigstoreWorkerSupport   │
                                          │  (worker loading trait)  │
                                          └────────┬─────────────────┘
                                                   │ classloader isolation
                              ┌────────────────────▼──────────────────────┐
                              │  contrib/sigstore/worker/                  │
                              │  SigstoreVerifyWorkerImpl                  │
                              │  (sigstore-java in isolated classloader)   │
                              └───────────────────────────────────────────┘
```

The design follows Mill's established patterns:

1. **Contrib module** (`contrib/sigstore`) -- user-facing trait that hooks into resolution
2. **Worker isolation** -- `sigstore-java` and its ~34 transitive dependencies run in a
   separate classloader, preventing classpath pollution
3. **Simple API boundary** -- plain Scala types (Strings, Seqs) cross the classloader
   boundary, no sigstore-java types leak into Mill's main classpath
4. **Configuration file** -- `.mill-sigstore-trust` for rule-based verification policies,
   manageable by security teams without touching Scala code

### 2.2 Key design decisions

1. **Post-resolution hook, not inline.** Verification runs after artifacts are downloaded,
   not during resolution. This keeps Phase 1.a clean and makes verification optional.

2. **Worker isolation is mandatory.** sigstore-java v2.0.0 brings ~34 transitive jars
   including gRPC, Guava, Protobuf, and Bouncy Castle. These must not be on Mill's main
   classpath.

3. **Rule-based policy, not all-or-nothing.** Organizations need to `require` Sigstore for
   their internal artifacts while `skip`ping verification for unsigned OSS dependencies.

4. **The `verify()` call makes zero HTTP requests.** sigstore-java's `KeylessVerifier`
   performs all verification locally using a cached TUF trust root. Only the initial
   `build()` fetches the trust root (cached for 24 hours on disk). This means verification
   adds negligible latency.

---

## 3. Phase 1.a Compatibility: Required Change

### 3.1 Add `repositoryUrl` to `ResolvedArtifact`

The Sigstore verification layer needs to know WHERE an artifact was fetched from in order
to construct the URL for the `.sigstore.json` bundle.

**In `MavenResolverSupport.scala` (Phase 1.a worker API):**

```scala
case class ResolvedArtifact(
    groupId: String,
    artifactId: String,
    version: String,
    classifier: String,
    path: String,
    repositoryUrl: String   // ← NEW: base URL of the repository it was resolved from
)
```

Maven Resolver's `ArtifactResult.getRepository()` already provides this. The worker
populates it trivially:

```scala
val repoUrl = artifactResult.getRepository match {
  case remote: RemoteRepository => remote.getUrl
  case _ => ""  // local repository
}
ResolvedArtifact(
    groupId = ..., artifactId = ..., version = ...,
    classifier = ..., path = ...,
    repositoryUrl = repoUrl
)
```

**For the Coursier backend:** When the Coursier backend is active, `repositoryUrl` can be
populated from the `MavenRepository.root` that resolved the artifact. This is available
from `Fetch.Result`'s `extraArtifacts` and `detailedArtifacts0` entries. If not immediately
available, it can be set to `""` (empty) -- the Sigstore verification module will skip
artifacts without a repository URL.

**This is a ~5-line change to Phase 1.a.**

### 3.2 Expose `ResolvedArtifact` from resolution methods

Currently the Phase 1.a design has `resolveArtifacts()` returning `Seq[ResolvedArtifact]`
through the worker API. The Sigstore module needs access to these from the
`CoursierModule.Resolver` level.

**Option:** Add a method to `CoursierModule.Resolver` (or the Sigstore module overrides
resolution tasks) that returns `Seq[ResolvedArtifact]` alongside the `Seq[PathRef]`.
The simplest path: the Sigstore module calls the worker API directly for the deps it
wants to verify, or caches the `ResolvedArtifact` data in a task.

The recommended approach is to have the Maven Resolver worker cache the mapping
`path -> ResolvedArtifact` for the most recent resolution, and expose a method to
retrieve it. This avoids resolving twice.

---

## 4. Worker API

### 4.1 API trait

Defined in `contrib/sigstore/src/mill/contrib/sigstore/api/SigstoreVerifyApi.scala`:

```scala
package mill.contrib.sigstore.api

/**
 * API boundary between Mill and the Sigstore verification worker classloader.
 * All types are plain Scala/Java types -- no sigstore-java types cross this boundary.
 */
trait SigstoreVerifyApi extends AutoCloseable {

  /**
   * Initialize the verifier. Must be called once before verify().
   *
   * @param trustedRootPath  path to a trusted_root.json file for offline/custom verification.
   *                         If empty, uses Sigstore public defaults (TUF update, cached 24h).
   */
  def initialize(trustedRootPath: String): Unit

  /**
   * Verify a single artifact against its Sigstore bundle.
   *
   * @param artifactPath   absolute path to the downloaded artifact
   * @param bundleJson     contents of the .sigstore.json bundle
   * @param expectedIssuer OIDC issuer to match (exact string), e.g.
   *                       "https://token.actions.githubusercontent.com"
   * @param expectedSubject SAN pattern to match (regex), e.g.
   *                        "https://github.com/apache/.*"
   * @return verification result
   */
  def verify(
      artifactPath: String,
      bundleJson: String,
      expectedIssuer: String,
      expectedSubject: String
  ): VerifyResult
}

/** Result of a Sigstore verification attempt. */
sealed trait VerifyResult
object VerifyResult {
  /** Signature verified successfully. */
  case class Verified(
      signerIdentity: String,  // the SAN from the Fulcio certificate
      issuer: String,          // the OIDC issuer
      logIndex: Long           // Rekor transparency log index
  ) extends VerifyResult

  /** Verification failed. */
  case class Failed(reason: String) extends VerifyResult

  /** Bundle could not be parsed. */
  case class BundleError(reason: String) extends VerifyResult
}
```

**Key design points:**

- `initialize()` is called once per worker lifetime. The `KeylessVerifier` instance is
  built here and reused (it's immutable and thread-safe).
- `verify()` takes the bundle JSON as a String rather than a path, because the contrib
  module downloads it via HTTP and may not persist it to disk.
- All return types are plain case classes -- no sigstore-java types leak out.
- `expectedIssuer` and `expectedSubject` come from the rule configuration.

### 4.2 Worker implementation

In `contrib/sigstore/worker/src/mill/contrib/sigstore/worker/SigstoreVerifyWorkerImpl.scala`:

```scala
package mill.contrib.sigstore.worker

import dev.sigstore.KeylessVerifier
import dev.sigstore.VerificationOptions
import dev.sigstore.VerificationOptions.CertificateMatcher
import dev.sigstore.bundle.Bundle
import dev.sigstore.strings.StringMatcher
import dev.sigstore.trustroot.{SigstoreTrustedRoot, TrustedRootProvider}
import mill.contrib.sigstore.api.{SigstoreVerifyApi, VerifyResult}

import java.io.StringReader
import java.nio.file.Paths

class SigstoreVerifyWorkerImpl extends SigstoreVerifyApi {
  private var verifier: KeylessVerifier = _

  override def initialize(trustedRootPath: String): Unit = {
    val builder = KeylessVerifier.builder()
    if (trustedRootPath.nonEmpty) {
      builder.trustedRootProvider(
        TrustedRootProvider.from(Paths.get(trustedRootPath))
      )
    } else {
      builder.sigstorePublicDefaults()
    }
    verifier = builder.build()
  }

  override def verify(
      artifactPath: String,
      bundleJson: String,
      expectedIssuer: String,
      expectedSubject: String
  ): VerifyResult = {
    try {
      val bundle = Bundle.from(new StringReader(bundleJson))
      val options = VerificationOptions.builder()
        .addCertificateMatchers(
          CertificateMatcher.fulcio()
            .issuer(StringMatcher.string(expectedIssuer))
            .subjectAlternativeName(StringMatcher.regex(expectedSubject))
            .build()
        ).build()

      verifier.verify(Paths.get(artifactPath), bundle, options)

      // If we get here, verification succeeded. Extract identity from bundle.
      val cert = bundle.getCertPath.getCertificates.get(0)
          .asInstanceOf[java.security.cert.X509Certificate]
      val san = cert.getSubjectAlternativeNames.iterator().next()
      val identity = san.get(1).toString

      VerifyResult.Verified(
        signerIdentity = identity,
        issuer = expectedIssuer,
        logIndex = bundle.getEntries.get(0).getLogIndex
      )
    } catch {
      case e: dev.sigstore.KeylessVerificationException =>
        VerifyResult.Failed(e.getMessage)
      case e: dev.sigstore.bundle.BundleParseException =>
        VerifyResult.BundleError(e.getMessage)
      case e: Exception =>
        VerifyResult.Failed(s"Unexpected error: ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
  }

  override def close(): Unit = {
    // KeylessVerifier has no resources to close
  }
}
```

**Performance characteristics:**

- `initialize()`: one-time TUF update (~200-500ms first run, 0ms from disk cache thereafter)
- `verify()`: pure CPU work -- signature check, certificate chain validation, Merkle
  inclusion proof. Sub-millisecond per artifact. **Zero HTTP calls.**
- The `KeylessVerifier` is immutable and thread-safe; built once, reused for all artifacts.

---

## 5. Contrib Module

### 5.1 Build definition

In `contrib/package.mill`, add:

```scala
object sigstore extends ContribModule {
  object api extends MillPublishJavaModule  // API trait crossing classloader boundary

  def moduleDeps = Seq(sigstore.api)
  def compileModuleDeps = Seq(build.libs.javalib)
  def testModuleDeps = super.testModuleDeps ++ Seq(build.libs.javalib)

  def localTestExtraModules =
    super.localTestExtraModules ++ Seq(worker)

  object worker extends MillPublishScalaModule {
    def compileModuleDeps = Seq(sigstore.api)
    def mvnDeps = Seq(
      mvn"dev.sigstore:sigstore-java:2.0.0"
    )
  }
}
```

This follows the exact pattern used by `contrib/scoverage`:

- **`api`** sub-module -- contains `SigstoreVerifyApi` trait and `VerifyResult` types.
  Published as `MillPublishJavaModule` (no Scala dependency). These types are on Mill's
  main classpath and cross the classloader boundary.
- **`worker`** sub-module -- contains `SigstoreVerifyWorkerImpl` with the heavy
  sigstore-java dependency (~22 MB). Resolved and loaded into an isolated classloader
  at runtime, NOT on Mill's main classpath.
- **Main module** -- depends on `api`, contains `SigstoreVerifyModule` trait and
  orchestration logic.

### 5.2 Worker loading

In `contrib/sigstore/src/mill/contrib/sigstore/SigstoreWorkerSupport.scala`:

```scala
package mill.contrib.sigstore

import mill.*
import mill.javalib.CoursierModule
import mill.util.Jvm

import scala.annotation.nowarn

private[sigstore] trait SigstoreWorkerSupport extends CoursierModule {

  private def sigstoreWorkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-contrib-sigstore-worker")
    ))
  }

  private def sigstoreWorkerClassloader: Task.Worker[ClassLoader & AutoCloseable] =
    Task.Worker {
      val classPath = sigstoreWorkerClasspath().map(_.path)
      Jvm.createClassLoader(classPath = classPath, parent = getClass.getClassLoader)
    }

  @nowarn("msg=.*Workers should implement AutoCloseable.*")
  private[sigstore] def sigstoreWorker: Task.Worker[api.SigstoreVerifyApi] =
    Task.Worker {
      val worker = sigstoreWorkerClassloader()
        .loadClass("mill.contrib.sigstore.worker.SigstoreVerifyWorkerImpl")
        .getConstructor().newInstance()
        .asInstanceOf[api.SigstoreVerifyApi]
      val trustRoot = sigstoreTrustedRootPath()
      worker.initialize(trustRoot)
      worker
    }
}
```

This follows the PGP worker pattern exactly:
1. Resolve worker JAR at build time via `Dep.millProjectModule`
2. Create isolated classloader via `Jvm.createClassLoader`
3. Load worker class dynamically and cast to API trait
4. `Task.Worker` keeps the worker (and its `KeylessVerifier`) alive for the build session

### 5.3 User-facing trait

In `contrib/sigstore/src/mill/contrib/sigstore/SigstoreVerifyModule.scala`:

```scala
package mill.contrib.sigstore

import mill.*
import mill.javalib.JavaModule

/**
 * Mix this into a JavaModule to verify Sigstore signatures on resolved dependencies.
 *
 * By default, reads verification rules from `.mill-sigstore-trust` in the project root.
 * Override [[sigstoreRules]] to customize programmatically.
 *
 * Example:
 * {{{
 *   import mill.contrib.sigstore.SigstoreVerifyModule
 *
 *   object myModule extends JavaModule with SigstoreVerifyModule
 * }}}
 */
trait SigstoreVerifyModule extends JavaModule with SigstoreWorkerSupport {

  /**
   * Path to the Sigstore trust root file. Empty string = use Sigstore public defaults.
   * Override for air-gapped environments or internal Sigstore deployments.
   */
  def sigstoreTrustedRootPath: T[String] = ""

  /**
   * Verification rules. Default: read from `.mill-sigstore-trust` in workspace root.
   */
  def sigstoreRules: Task[Seq[SigstoreRule]] = Task {
    val configFile = Task.workspace / ".mill-sigstore-trust"
    if (os.exists(configFile)) SigstoreRuleParser.parse(os.read(configFile))
    else Seq.empty
  }

  /**
   * Verify resolved dependencies after resolution.
   * Runs as a post-resolution step; does not re-resolve.
   */
  def sigstoreVerify: T[SigstoreVerifyReport] = Task {
    val rules = sigstoreRules()
    if (rules.isEmpty) {
      Task.log.info("No Sigstore verification rules configured; skipping")
      SigstoreVerifyReport(Seq.empty, Seq.empty, Seq.empty)
    } else {
      val artifacts = resolvedMvnDepsArtifacts()
      val worker = sigstoreWorker()
      SigstoreVerifier.verifyAll(artifacts, rules, worker, Task.log)
    }
  }

  /**
   * Override resolvedMvnDeps to optionally enforce Sigstore verification.
   * When rules with policy=require exist, resolution fails if verification fails.
   */
  override def resolvedMvnDeps: T[Seq[PathRef]] = Task {
    val paths = super.resolvedMvnDeps()
    val rules = sigstoreRules()
    if (rules.exists(_.policy == SigstorePolicy.Require)) {
      val report = sigstoreVerify()
      if (report.failures.nonEmpty) {
        val failMsg = report.failures
          .map(f => s"  ${f.groupId}:${f.artifactId}:${f.version} - ${f.reason}")
          .mkString("\n")
        Result.Failure(
          s"Sigstore verification failed for ${report.failures.size} artifact(s):\n$failMsg"
        )
      }
    }
    paths
  }

  /**
   * Resolved artifact details with repository URLs.
   * Phase 1.a worker provides this; Coursier backend provides a best-effort mapping.
   */
  private def resolvedMvnDepsArtifacts(): Seq[ArtifactInfo] = {
    // Implementation depends on Phase 1.a's ResolvedArtifact being available
    // via CoursierModule.Resolver or cached from the most recent resolution
    ???
  }
}
```

---

## 6. Configuration: `.mill-sigstore-trust`

### 6.1 Format

A simple, line-oriented format readable by security teams:

```properties
# .mill-sigstore-trust
#
# Sigstore verification rules for dependency resolution.
# Rules are evaluated top-to-bottom; first match wins.
#
# Syntax:
#   [policy] [groupId-pattern] [repository-pattern] [issuer] [subject-pattern]
#
# Policies:
#   require        - fail build if .sigstore.json is missing or verification fails
#   when-available - verify if .sigstore.json exists, skip silently if not
#   skip           - do not check (saves HTTP request for .sigstore.json)

# Internal artifacts: MUST be signed by our CI
require  com.mycompany.*  *  https://token.actions.githubusercontent.com  https://github.com/mycompany/.*

# Apache projects: verify when signed
when-available  org.apache.*  *  https://token.actions.githubusercontent.com  https://github.com/apache/.*

# Typelevel ecosystem
when-available  org.typelevel.*  *  https://token.actions.githubusercontent.com  https://github.com/typelevel/.*

# Everything else: skip
skip  *  *  *  *
```

### 6.2 Data model

```scala
package mill.contrib.sigstore

/** A single Sigstore verification rule. */
case class SigstoreRule(
    policy: SigstorePolicy,
    groupIdPattern: String,      // glob pattern, e.g. "com.mycompany.*"
    repositoryPattern: String,   // glob pattern, e.g. "https://nexus.corp.com/*"
    expectedIssuer: String,      // exact OIDC issuer URL
    expectedSubject: String      // regex for SAN matching
)

enum SigstorePolicy {
  case Require        // fail if missing or invalid
  case WhenAvailable  // verify if present, skip if missing
  case Skip           // don't even try to fetch .sigstore.json
}
```

### 6.3 Rule matching

```scala
object SigstoreRuleMatcher {
  /**
   * Find the first matching rule for an artifact. Rules are evaluated in order.
   * Returns None if no rule matches (equivalent to Skip).
   */
  def findRule(
      groupId: String,
      repositoryUrl: String,
      rules: Seq[SigstoreRule]
  ): Option[SigstoreRule] = {
    rules.find { rule =>
      globMatches(rule.groupIdPattern, groupId) &&
      globMatches(rule.repositoryPattern, repositoryUrl)
    }
  }

  private def globMatches(pattern: String, value: String): Boolean = {
    if (pattern == "*") true
    else if (pattern.endsWith(".*"))
      value.startsWith(pattern.dropRight(2)) || value == pattern.dropRight(2)
    else if (pattern.endsWith("*"))
      value.startsWith(pattern.dropRight(1))
    else pattern == value
  }
}
```

### 6.4 Programmatic configuration (alternative to config file)

Users can also override `sigstoreRules` directly in `build.mill`:

```scala
object myModule extends JavaModule with SigstoreVerifyModule {
  override def sigstoreRules = Task {
    Seq(
      SigstoreRule(
        policy = SigstorePolicy.Require,
        groupIdPattern = "com.mycompany.*",
        repositoryPattern = "*",
        expectedIssuer = "https://token.actions.githubusercontent.com",
        expectedSubject = "https://github.com/mycompany/.*"
      ),
      SigstoreRule(
        policy = SigstorePolicy.Skip,
        groupIdPattern = "*",
        repositoryPattern = "*",
        expectedIssuer = "*",
        expectedSubject = "*"
      )
    )
  }
}
```

---

## 7. Verification Flow

### 7.1 Per-artifact flow

```
For each resolved artifact:
  1. Match against rules (first match wins)
     ├─ No match or Skip → done, move to next artifact
     ├─ Require or WhenAvailable → continue
     │
  2. Construct bundle URL: {repositoryUrl}/{groupId-as-path}/{artifactId}/{version}/{filename}.sigstore.json
     │
  3. Fetch .sigstore.json via HTTP GET
     ├─ 404 + WhenAvailable → done (no bundle, not enforced)
     ├─ 404 + Require → FAIL ("Sigstore bundle required but not found")
     ├─ 200 → continue
     │
  4. Call worker.verify(artifactPath, bundleJson, expectedIssuer, expectedSubject)
     ├─ Verified → log success, record in report
     ├─ Failed → FAIL if Require, WARN if WhenAvailable
     └─ BundleError → FAIL if Require, WARN if WhenAvailable
```

### 7.2 Bundle URL construction

The `.sigstore.json` bundle lives alongside the artifact on the repository:

```
https://repo1.maven.org/maven2/dev/sigstore/sigstore-java/2.0.0/sigstore-java-2.0.0.jar
https://repo1.maven.org/maven2/dev/sigstore/sigstore-java/2.0.0/sigstore-java-2.0.0.jar.sigstore.json
                                                                                          ^^^^^^^^^^^^^^^^
```

Construction from `ResolvedArtifact`:

```scala
def bundleUrl(artifact: ResolvedArtifact): String = {
  val base = artifact.repositoryUrl.stripSuffix("/")
  val groupPath = artifact.groupId.replace('.', '/')
  val classifier = if (artifact.classifier.nonEmpty) s"-${artifact.classifier}" else ""
  val filename = s"${artifact.artifactId}-${artifact.version}${classifier}.jar"
  s"$base/$groupPath/${artifact.artifactId}/${artifact.version}/$filename.sigstore.json"
}
```

### 7.3 HTTP fetching for bundles

The bundle fetch should use Maven Resolver's transport (when the maven backend is active)
or a simple HTTP client. Since this is a contrib module, using `java.net.http.HttpClient`
(available since Java 11) is simplest and avoids additional dependencies:

```scala
private def fetchBundle(url: String): Option[String] = {
  val client = java.net.http.HttpClient.newHttpClient()
  val request = java.net.http.HttpRequest.newBuilder()
    .uri(java.net.URI.create(url))
    .timeout(java.time.Duration.ofSeconds(10))
    .build()
  val response = client.send(request, BodyHandlers.ofString())
  if (response.statusCode() == 200) Some(response.body())
  else None
}
```

**Important:** This HTTP call should respect proxy settings. When the maven backend is
active, the JVM system properties for proxy configuration (set from `settings.xml`) will
be picked up by `java.net.http.HttpClient` automatically.

### 7.4 Verification report

```scala
case class SigstoreVerifyReport(
    verified: Seq[VerifiedArtifact],
    skipped: Seq[SkippedArtifact],
    failures: Seq[FailedArtifact]
)

case class VerifiedArtifact(
    groupId: String, artifactId: String, version: String,
    signerIdentity: String, logIndex: Long
)
case class SkippedArtifact(
    groupId: String, artifactId: String, version: String,
    reason: String  // "no rule matched", "policy=skip", "no bundle found"
)
case class FailedArtifact(
    groupId: String, artifactId: String, version: String,
    reason: String
)
```

---

## 8. Internal Sigstore Deployment Support

Large organizations may run their own Sigstore infrastructure (private Fulcio CA, private
Rekor, private TUF root). This is fully supported.

### 8.1 Custom trust root

```properties
# .mill-sigstore-trust
@trust-root /path/to/internal/trusted_root.json
```

Or programmatically:

```scala
object myModule extends JavaModule with SigstoreVerifyModule {
  override def sigstoreTrustedRootPath = "/etc/sigstore/trusted_root.json"
}
```

The `trusted_root.json` file contains the organization's Fulcio CA certificates and Rekor
public keys. It follows the standard Sigstore trust root protobuf format.

### 8.2 Air-gapped environments

For fully offline verification:

1. Export `trusted_root.json` from the organization's Sigstore infrastructure
2. Bundle it in the repository (e.g., `.sigstore/trusted_root.json`)
3. Configure: `@trust-root .sigstore/trusted_root.json`
4. Verification is fully offline -- `KeylessVerifier.verify()` makes zero HTTP calls,
   and the trust root is loaded from the local file

---

## 9. Module Structure

```
contrib/sigstore/
  readme.adoc                              # required by ContribModule
  src/mill/contrib/sigstore/
    SigstoreVerifyModule.scala             # user-facing trait (mixin for JavaModule)
    SigstoreWorkerSupport.scala            # worker classloader loading
    SigstoreRule.scala                     # data model: SigstoreRule, SigstorePolicy
    SigstoreRuleParser.scala               # parser for .mill-sigstore-trust config file
    SigstoreRuleMatcher.scala              # rule matching logic
    SigstoreVerifier.scala                 # orchestration: fetch bundles, call worker, build report
    SigstoreVerifyReport.scala             # report data model
  api/                                     # ← separate MillPublishJavaModule
    src/mill/contrib/sigstore/api/
      SigstoreVerifyApi.scala              # API trait crossing classloader boundary
      VerifyResult.scala                   # result types crossing classloader boundary
  worker/                                  # ← separate MillPublishScalaModule
    src/mill/contrib/sigstore/worker/
      SigstoreVerifyWorkerImpl.scala       # implementation using sigstore-java
  test/
    src/mill/contrib/sigstore/
      SigstoreRuleParserTests.scala        # unit tests for config parsing
      SigstoreRuleMatcherTests.scala       # unit tests for rule matching
      SigstoreVerifyModuleTests.scala      # integration tests with test bundles
```

The `api/` and `worker/` directories are separate Mill sub-modules (declared in the
parent `contrib/package.mill`), not source subdirectories of the main module.

**Estimated size:** ~500-600 lines of Scala (excluding tests).

---

## 10. Dependency Analysis

### 10.1 Worker classpath (isolated)

The `contrib/sigstore/worker` module declares:

```scala
def mvnDeps = Seq(mvn"dev.sigstore:sigstore-java:2.0.0")
```

This pulls in transitively:

| Dependency | Size | Purpose | Used During Verification? |
|---|---|---|---|
| `dev.sigstore:sigstore-java:2.0.0` | ~1.5 MB | Core library | Yes |
| `dev.sigstore:protobuf-specs` | ~0.3 MB | Bundle format | Yes |
| `com.google.protobuf:protobuf-java-util` | ~1.8 MB | Protobuf JSON | Yes |
| `org.bouncycastle:bcpkix-jdk18on` | ~0.6 MB | X.509/PKIX | Yes |
| `org.bouncycastle:bcutil-jdk18on` | ~0.4 MB | BC utilities | Yes |
| `org.bouncycastle:bcprov-jdk18on` | ~6.0 MB | BC provider | Yes |
| `com.google.http-client:google-http-client-*` | ~0.8 MB | HTTP (TUF update) | Only during initialize() |
| `com.google.code.gson:gson` | ~0.3 MB | JSON parsing | Yes |
| `io.github.erdtman:java-json-canonicalization` | ~0.02 MB | JSON canon. | Yes |
| `com.google.guava:guava` | ~3.0 MB | Utilities | Transitively |
| `io.grpc:grpc-*` (netty-shaded, protobuf, stub) | ~7.0 MB | gRPC | **No** (signing only) |
| `com.google.oauth-client:google-oauth-client-*` | ~0.5 MB | OAuth | **No** (signing only) |

**Total: ~22 MB on the worker classpath.**

The gRPC and OAuth dependencies are never loaded during verification. They could
potentially be excluded from the worker module's dependency declaration, but this is not
officially supported by sigstore-java. Safe to leave them on the classpath -- they occupy
disk space but are never classloaded.

### 10.2 Main classpath impact

**Zero.** The contrib module's compile dependency is only on `build.libs.javalib` (for
`JavaModule`, `CoursierModule`). The `api/` package contains only plain Scala case classes
and traits. No sigstore-java types appear on Mill's main classpath.

---

## 11. Testing Strategy

### 11.1 Unit tests (no network)

- **Rule parsing:** parse valid/invalid `.mill-sigstore-trust` files
- **Rule matching:** groupId glob patterns, repository patterns, first-match semantics
- **Bundle URL construction:** from `ResolvedArtifact` to correct URL

### 11.2 Integration tests (with test bundles)

The sigstore-java repository contains test bundles in its test resources. We can vendor
a small test bundle + trusted root for offline integration testing:

1. Sign a test artifact using sigstore-java's `KeylessSigner` in test setup
2. Verify it using the module's verification flow
3. Test all three policies: `require`, `when-available`, `skip`
4. Test failure cases: invalid bundle, identity mismatch, missing bundle with `require`

### 11.3 Example test (subprocess)

```
# example/javalib/security/sigstore-verify/build.mill
import mill.contrib.sigstore.SigstoreVerifyModule

object myModule extends JavaModule with SigstoreVerifyModule

# .mill-sigstore-trust
when-available  *  *  https://token.actions.githubusercontent.com  https://github.com/.*
```

```bash
> ./mill myModule.sigstoreVerify
```

---

## 12. User Experience

### 12.1 Getting started

**Minimal setup (2 files):**

```scala
// build.mill
import mill.contrib.sigstore.SigstoreVerifyModule

object myProject extends JavaModule with SigstoreVerifyModule
```

```properties
# .mill-sigstore-trust
when-available  *  *  https://token.actions.githubusercontent.com  https://github.com/.*
```

This verifies Sigstore signatures on any artifact that has them, without breaking builds
for unsigned artifacts.

### 12.2 Enterprise lockdown

```properties
# .mill-sigstore-trust
require         com.mycompany.*  *  https://token.actions.githubusercontent.com  https://github.com/mycompany/.*
when-available  org.apache.*     *  https://token.actions.githubusercontent.com  https://github.com/apache/.*
skip            *                *  *                                            *
```

### 12.3 CLI commands

```bash
# Run verification and print report
./mill myModule.sigstoreVerify

# Normal build (verification runs automatically if require rules exist)
./mill myModule.compile
```

### 12.4 Output example

```
Sigstore verification report:
  Verified (3):
    com.mycompany:core:1.2.0      signed by https://github.com/mycompany/core/.github/workflows/release.yml@refs/tags/v1.2.0
    com.mycompany:utils:1.1.0     signed by https://github.com/mycompany/utils/.github/workflows/release.yml@refs/tags/v1.1.0
    org.apache:commons-lang3:3.14 signed by https://github.com/apache/commons-lang/.github/workflows/maven.yml@refs/tags/rel/commons-lang-3.14.0
  Skipped (47):
    47 artifacts matched skip policy or had no .sigstore.json bundle
  Failed (0)
```

---

## 13. Implementation Phases

### 13.1 Phase 1.a change (prerequisite, ~5 lines)

Add `repositoryUrl: String` to `ResolvedArtifact` in the Maven Resolver worker API.

### 13.2 Phase 1.b implementation (~500-600 lines)

| Step | What | Lines | Dependencies |
|------|------|-------|-------------|
| 1 | API trait + result types | ~40 | None |
| 2 | Worker implementation | ~60 | sigstore-java |
| 3 | Worker loading (classloader) | ~30 | Mill patterns |
| 4 | Rule data model + parser | ~80 | None |
| 5 | Rule matcher | ~30 | None |
| 6 | Verifier orchestration (fetch + verify) | ~100 | java.net.http |
| 7 | `SigstoreVerifyModule` trait | ~80 | Mill JavaModule |
| 8 | Report model + formatting | ~40 | None |
| 9 | Build definition (`package.mill`) | ~20 | None |
| 10 | Tests | ~200 | Mill testkit |

**Total: ~500-600 lines production code + ~200 lines tests.**

---

## 14. Open Questions

1. **Should `sigstoreVerify` run automatically during `resolvedMvnDeps`?** The current
   design runs it automatically only when `require` rules exist. An alternative: always
   run as a separate task that users invoke explicitly (`./mill myModule.sigstoreVerify`).

2. **Cache verified status?** Once an artifact is verified, should we cache the result
   so subsequent builds skip re-verification? The artifact content doesn't change (same
   PathRef), so this is safe. Could use a `.sigstore-verified` marker file alongside the
   cached artifact.

3. **Bundle caching?** Should downloaded `.sigstore.json` bundles be cached in Mill's
   `out/` directory? This saves HTTP requests on subsequent builds and enables offline
   re-verification.

4. **Interaction with Maven Resolver's trusted checksums?** If both trusted checksums
   (Phase 1.a) and Sigstore verification (Phase 1.b) are active, they provide
   complementary guarantees: checksums verify integrity, Sigstore verifies authenticity.
   They should both run independently.

5. **Should this work with the Coursier backend too?** In principle, yes -- `repositoryUrl`
   could be populated from Coursier's `MavenRepository.root`. This would allow Sigstore
   verification regardless of which resolution backend is active. The fetch for
   `.sigstore.json` uses `java.net.http.HttpClient` directly, not the resolver's transport.

---

## Appendix A: sigstore-java Key API Reference

```
Package: dev.sigstore

KeylessVerifier
  .builder() → KeylessVerifier.Builder
    .sigstorePublicDefaults() → Builder       # uses public TUF root
    .trustedRootProvider(TrustedRootProvider)  # custom/offline trust root
    .build() → KeylessVerifier                # may do TUF HTTP update
  .verify(Path, Bundle, VerificationOptions)  # ZERO HTTP calls
    throws KeylessVerificationException

Bundle
  .from(Reader) → Bundle                     # parse .sigstore.json
  .from(Path, Charset) → Bundle
    throws BundleParseException

VerificationOptions
  .builder() → Builder
    .addCertificateMatchers(CertificateMatcher...) → Builder
    .build() → VerificationOptions

CertificateMatcher                            # extends Predicate<X509Certificate>
  .fulcio() → FulcioCertificateMatcher.Builder
    .issuer(StringMatcher) → Builder
    .subjectAlternativeName(StringMatcher) → Builder
    .build() → CertificateMatcher

StringMatcher                                 # extends Predicate<String>
  .string(String exact) → StringMatcher       # exact equality
  .regex(String pattern) → StringMatcher      # regex match

TrustedRootProvider                           # @FunctionalInterface
  .from(Path trustedRoot) → TrustedRootProvider   # offline, from file
  .from(SigstoreTufClient.Builder) → TrustedRootProvider  # TUF-based
```

## Appendix B: Sigstore Bundle Format (.sigstore.json)

```json
{
  "mediaType": "application/vnd.dev.sigstore.bundle.v0.3+json",
  "verificationMaterial": {
    "x509CertificateChain": {
      "certificates": [{"rawBytes": "<base64 DER cert>"}]
    },
    "tlogEntries": [{
      "logIndex": "41958702",
      "logId": {"keyId": "<base64>"},
      "kindVersion": {"kind": "hashedrekord", "version": "0.0.1"},
      "integratedTime": "1697034484",
      "inclusionProof": {
        "logIndex": "37795271",
        "rootHash": "<base64>",
        "treeSize": "37795272",
        "hashes": ["<base64>", ...],
        "checkpoint": {"envelope": "<signed checkpoint>"}
      },
      "canonicalizedBody": "<base64>"
    }]
  },
  "messageSignature": {
    "messageDigest": {"algorithm": "SHA2_256", "digest": "<base64>"},
    "signature": "<base64>"
  }
}
```
