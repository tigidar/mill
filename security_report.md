# Artifact Resolution Security Report

**Date:** 2026-04-09
**Context:** Maven Resolver integration into Mill (Phase 1)
**Prerequisites:** [design_exploration_report.md](design_exploration_report.md), [design_phase1_detailed.md](design_phase1_detailed.md)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [The Coursier Security Problem](#2-the-coursier-security-problem)
3. [Maven Resolver Security Features](#3-maven-resolver-security-features)
4. [settings.xml as a Security Control Plane](#4-settingsxml-as-a-security-control-plane)
5. [Broader Artifact Resolution Security Landscape](#5-broader-artifact-resolution-security-landscape)
6. [Comparison Matrix: Coursier vs Maven Resolver vs Gradle](#6-comparison-matrix)
7. [What's Still Lacking (Even with Maven Resolver)](#7-whats-still-lacking)
8. [Phase 1 Security Feature Assessment](#8-phase-1-security-feature-assessment)
9. [Recommendations and Future Work](#9-recommendations-and-future-work)

---

## 1. Executive Summary

This report evaluates the security properties of Maven Resolver compared to Coursier,
surveys the broader artifact resolution security landscape, and assesses what Phase 1
of the Maven Resolver integration delivers from a security perspective.

**Key findings:**

- **Maven Resolver fixes the critical transport security bug** in Coursier/OkHttp
  (hostname verification through proxies) by using Apache HttpClient, which correctly
  verifies TLS certificates in all connection scenarios.

- **`settings.xml` gives enterprises a standard security control plane** for mirror
  enforcement, credential management, proxy authentication, and per-repository checksum
  policies -- all of which Coursier either lacks or implements in non-standard ways.

- **Maven Resolver's trusted checksums feature** provides dependency verification
  comparable to Gradle's `verification-metadata.xml`, which neither Coursier nor Maven's
  own CLI tool provides by default.

- **Neither Maven Resolver nor Coursier verifies PGP signatures.** This is a gap across
  the entire JVM ecosystem (Gradle is the exception with its `verification-metadata.xml`
  PGP support).

- **Phase 1 delivers substantial security improvements with minimal effort.** Transport
  security, mirror enforcement, authenticated proxies, and strict checksums are essentially
  "free" by virtue of using Maven Resolver + `settings.xml`. More advanced features (PGP
  verification, dependency locking, SBOM) can be added incrementally.

- **The broader landscape is shifting** toward Sigstore (keyless signing), SLSA provenance
  attestations, and mandatory SBOMs. Mill should plan for these, but they are not blocking
  for Phase 1.

---

## 2. The Coursier Security Problem

### 2.1 The OkHttp Hostname Verification Bug

Coursier uses OkHttp as its HTTP transport. OkHttp has a known deficiency in TLS hostname
verification when connections go through an HTTP CONNECT proxy.

**The standard HTTPS-through-proxy flow:**

1. Client connects to the proxy via plain HTTP
2. Client sends `CONNECT target-host:443` to the proxy
3. Proxy establishes a TCP tunnel to the target
4. Client performs a TLS handshake **through the tunnel** directly with the target server
5. Client **must verify** the certificate presented matches `target-host`

**The bug:** Step 5 is not properly enforced in OkHttp. When connecting through a proxy,
OkHttp does not verify the hostname against the TLS certificate. The proxy (or any attacker
controlling the proxy) can present its own certificate or route the tunnel to a different
server, and OkHttp will accept the connection without flagging the hostname mismatch.

**CVE status:** No formal CVE has been assigned for this specific behavior, which makes
it harder to track. OkHttp maintainers have treated it as a design limitation rather than
a vulnerability. The Coursier team has acknowledged the issue and stated it is "no fix" --
they do not intend to address it.

**The attack vector -- MITM through corporate proxy:**

1. Developer or CI system is behind a mandatory corporate HTTP proxy
2. Mill/sbt/Coursier resolves dependencies through that proxy
3. An attacker who controls (or compromises) the proxy intercepts the CONNECT tunnel
4. Instead of tunneling to `repo1.maven.org`, the proxy routes to an attacker-controlled server
5. The attacker's server presents its own TLS certificate (which does NOT match `repo1.maven.org`)
6. OkHttp **accepts the connection** because hostname verification is broken through proxies
7. The attacker serves malicious JARs (backdoored libraries, trojaned build tool components)
8. Coursier caches these artifacts locally; subsequent builds reuse them silently
9. The malicious code executes with full access to the build environment

This is particularly dangerous because:

- **Corporate proxies are mandatory** -- every developer and CI system is subject to the bug
- **TLS inspection proxies** are common in enterprises, making it hard to distinguish
  legitimate proxy behavior from an actual attack
- **Cached artifacts persist** -- the poisoned artifact survives beyond the attack window
- **Build processes have elevated access** -- CI secrets, deployment credentials, signing
  keys, and source code are all accessible to build-time code
- **The Mill daemon JAR itself** is resolved via Coursier (`CoursierClient.scala`). A MITM
  at the proxy could serve a trojaned Mill daemon, which is worse than a compromised
  dependency because the daemon has full access to everything

**Certificate Transparency offers no protection** here -- the attacker doesn't need a
fraudulent certificate for `repo1.maven.org`. They can present any certificate (including
self-signed) because hostname verification is skipped entirely.

### 2.2 Other Coursier Security Gaps

From Coursier's official security documentation and our codebase analysis:

| Gap | Description | Impact |
|-----|-------------|--------|
| **No PGP signature verification** | `.asc` files can be fetched via API but are never verified | Artifacts cannot be authenticated to their publisher |
| **Accepts artifacts without checksums** | If no checksum is available, artifact is silently accepted | No integrity guarantee for some artifacts |
| **No verification metadata** | No equivalent to Gradle's `verification-metadata.xml` | No way to pin artifact contents across builds |
| **Non-configurable checksum policy** | Cannot make missing/invalid checksums a build failure | Enterprises cannot enforce strict integrity checks |
| **Indirect CVE exposure** | CVE-2022-46751 (XXE) and CVE-2022-37866 (path traversal) | Vulnerabilities in Coursier's dependency chain |
| **Non-standard credential format** | `COURSIER_CREDENTIALS` env var, proprietary format | Enterprise security teams cannot audit/manage it |
| **Non-standard mirror config** | `COURSIER_MIRRORS` env var, proprietary format | Not compatible with existing Maven/enterprise tooling |

### 2.3 How Maven Resolver Avoids the Transport Bug

Maven Resolver uses a pluggable transport architecture. The primary transport
(`maven-resolver-transport-http`) uses **Apache HttpClient 5.x**, which correctly performs
hostname verification in all connection scenarios:

- Apache HttpClient's `SSLConnectionSocketFactory` (4.x) and equivalent in 5.x correctly
  layers TLS verification on top of proxy tunnels
- The `DefaultHostnameVerifier` follows RFC 2818 / RFC 6125 for hostname matching,
  including Subject Alternative Names (SANs) and wildcard certificates
- Full certificate chain validation up to trusted root CA
- Certificate expiry checking
- Configurable CRL/OCSP revocation checking
- TLS protocol version and cipher suite enforcement

Apache HttpClient has its own historical CVEs (around SSL handling in very old 3.x
versions), but the modern 4.x and 5.x series have robust, well-audited TLS handling.

| Transport Provider | HTTP Library | Hostname Verification Through Proxy | Status |
|--------------------|--------------|-------------------------------------|--------|
| `maven-resolver-transport-http` | Apache HttpClient 5.x | **Correct** | Default since Maven 3.9 |
| `maven-resolver-transport-wagon` | Apache HttpClient 4.x (via Wagon) | **Correct** | Legacy, Maven 2/3 era |
| Coursier/OkHttp | OkHttp 3.x/4.x | **Broken** | "No fix" from maintainers |

---

## 3. Maven Resolver Security Features

### 3.1 Checksum Verification

Maven Resolver has a sophisticated, configurable checksum verification system.

**Supported algorithms:** MD5, SHA-1, SHA-256, SHA-512 (extensible via SPI).

**Default:** `SHA-1,MD5` -- configurable via:
```properties
aether.checksums.checksumAlgorithms=SHA-256,SHA-1
aether.checksums.downloadChecksumAlgorithms=SHA-256,SHA-1   # downloads only
aether.checksums.uploadChecksumAlgorithms=SHA-256,SHA-1     # uploads only
```

**Three-strategy verification pipeline** (stops at first success):

1. **Provided Checksums** -- injected before transport via `ProvidedChecksumsSource` SPI.
   Used for pre-known checksums (e.g., from a trusted checksums file).

2. **Remote Included Checksums (Smart Checksums)** -- extracted from HTTP response
   headers in a single round-trip, halving HTTP requests:
   - Maven Central: `x-checksum-sha1`, `x-checksum-md5`
   - Sonatype Nexus 2: `ETag` (SHA-1 only)
   - Google GCS: `x-goog-meta-checksum-sha1`, `x-goog-meta-checksum-md5`

3. **Remote External Checksums (Classic)** -- separate HTTP requests for checksum
   files (e.g., `artifact.jar.sha1`). Fallback when headers are unavailable.

**Checksum policy** -- configurable per-repository in `settings.xml`:

| Policy | Missing Checksum | Invalid Checksum |
|--------|-----------------|------------------|
| `fail` | **Build fails** | **Build fails** |
| `warn` (default) | Warning logged | Warning logged |
| `ignore` | Silently accepted | Silently accepted |

```xml
<repository>
  <releases><checksumPolicy>fail</checksumPolicy></releases>
  <snapshots><checksumPolicy>fail</checksumPolicy></snapshots>
</repository>
```

Global override via session: `session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL)`

**Critical difference from Coursier:** Coursier always accepts artifacts without checksums
silently. Maven Resolver's `checksumPolicy=fail` rejects them. This alone is a significant
security improvement.

### 3.2 Trusted Checksums (Dependency Verification)

Maven Resolver 1.9.0+ includes a **trusted checksums post-processor** -- the closest
equivalent to Gradle's `verification-metadata.xml`. This validates artifacts **after
resolution**, independent of the transport layer.

**Configuration:**
```properties
# Enable trusted checksums
aether.artifactResolver.postProcessor.trustedChecksums=true
aether.artifactResolver.postProcessor.trustedChecksums.failIfMissing=true
aether.artifactResolver.postProcessor.trustedChecksums.checksumAlgorithms=SHA-256
aether.artifactResolver.postProcessor.trustedChecksums.record=false
aether.artifactResolver.postProcessor.trustedChecksums.scope=all

# Storage backend: summary file (GNU coreutils format)
aether.trustedChecksumsSource.summaryFile=true
aether.trustedChecksumsSource.summaryFile.basedir=${session.rootDirectory}/.mvn/checksums
aether.trustedChecksumsSource.summaryFile.originAware=true
```

**Two built-in storage backends:**

1. **Summary File** -- GNU coreutils-compatible format, one file per algorithm:
   ```
   abc123def456  commons-lang/commons-lang/2.6/commons-lang-2.6.jar
   ```
   Compatible with `sha256sum --check` command-line tools.

2. **Sparse Directory** -- mirrors local repository layout with checksum files alongside
   artifact paths.

**Recording workflow:**
1. In a known-safe environment, set `trustedChecksums.record=true`
2. Resolve all dependencies -- checksums are captured
3. Commit the checksum files to version control
4. In CI/production, set `trustedChecksums.record=false` and `failIfMissing=true`
5. Any new/changed dependency causes a build failure until checksums are updated

**Comparison with Gradle's verification-metadata.xml:**

| Feature | Gradle | Maven Resolver Trusted Checksums |
|---------|--------|----------------------------------|
| Format | Single XML file | Summary files (coreutils) or sparse directory |
| Checksums | SHA-256, SHA-512, SHA-1, MD5 | SHA-256, SHA-512, SHA-1, MD5 (configurable) |
| PGP key fingerprints | Yes | No |
| Generation command | `--write-verification-metadata` | `trustedChecksums.record=true` |
| Per-repository awareness | No | Yes (`originAware=true`) |
| Built-in | Yes (Gradle 6.2+) | Yes (Maven Resolver 1.9.0+) |
| Default enabled | No | No |

### 3.3 Remote Repository Filtering

Maven Resolver includes a framework to restrict which repositories can serve which artifacts:

**Prefixes Filter** -- repositories publish prefix files listing what they contain. Maven
only queries repositories that claim to have the requested artifact path prefix.

**GroupId Filter** -- restricts which groupIds can come from which repositories:
```
# .mvn/rrf/groupId-corp-nexus.txt
org.apache.maven
org.springframework
!=org.springframework.experimental
!*  # reject everything not explicitly listed
```

**Configuration:**
```properties
aether.remoteRepositoryFilter.groupId=true
aether.remoteRepositoryFilter.groupId.basedir=${session.rootDirectory}/.mvn/rrf/
aether.remoteRepositoryFilter.groupId.record=true  # auto-record encountered groupIds
aether.remoteRepositoryFilter.prefixes=true
```

This is a defense against **dependency confusion attacks** -- by restricting which groupIds
can come from which repositories, an attacker cannot inject malicious packages by publishing
to an alternative repository.

**Coursier has no equivalent feature.**

### 3.4 Transport Security Configuration

Maven Resolver exposes fine-grained transport configuration:

```properties
# TLS protocol versions
aether.transport.apache.https.protocols=TLSv1.2,TLSv1.3

# Cipher suite restriction
aether.transport.apache.https.cipherSuites=TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256

# Connection management
aether.transport.http.connectTimeout=30000              # 30s
aether.transport.http.requestTimeout=1800000            # 30min
aether.transport.http.connectionMaxTtl=300              # seconds
aether.transport.http.maxConnectionsPerRoute=50

# Retry configuration
aether.transport.http.retryHandler.count=3
aether.transport.http.retryHandler.interval=5000        # ms between retries
aether.transport.http.retryHandler.serviceUnavailable=429,503

# Per-repository overrides
aether.transport.http.connectTimeout.central=60000
```

TLS verification uses standard Java keystore infrastructure:

| Property | Purpose |
|----------|---------|
| `javax.net.ssl.trustStore` | Path to trusted certificates keystore |
| `javax.net.ssl.trustStoreType` | Trust store format (`jks`, `pkcs12`) |
| `javax.net.ssl.keyStore` | Path to client private key keystore (for mTLS) |
| `javax.net.ssl.keyStoreType` | Key store format |

**Certificate pinning** is achievable by configuring a trust store containing only the
expected certificate(s) for the repository server. Enterprise deployments typically handle
this at the repository manager level or via corporate CA infrastructure.

### 3.5 Signing Capabilities (Publishing Side)

Maven Resolver includes two signing generators for deployment:

- **GnuPG Signer Generator** -- signs artifacts with GPG during deployment
- **Sigstore Signer Generator** -- signs with Sigstore (modern alternative to PGP)

```properties
aether.generator.gpg.enabled=true
aether.generator.gpg.useAgent=true
aether.generator.gpg.keyFilePath=maven-signing-key.key
aether.generator.gpg.keyFingerprint=<40-char-hex>

aether.generator.sigstore.enabled=true  # Sigstore support
```

These are for **producing** signatures during deployment, not for **verifying** signatures
on downloaded artifacts. Verification is covered in Section 7.

### 3.6 Extension Points for Custom Security

Maven Resolver's architecture provides several SPIs for security customization:

**`ProvidedChecksumsSource` SPI** -- inject known-good checksums before transport.
Integration point for organizational checksum databases.

**`TrustedChecksumsSource` SPI** -- custom post-resolution verification against an
organizational artifact registry.

**`RemoteRepositoryFilter` SPI** -- custom filtering logic beyond the built-in groupId
and prefix filters. Could implement:
- Vulnerability-aware filtering (block repos known to serve compromised artifacts)
- Time-based filtering (only accept artifacts published before a certain date)
- Approval workflow integration

**`RepositoryListener`** -- receives callbacks for `artifactResolved`, `artifactDownloaded`,
`metadataResolved`, etc. Enables:
- Custom audit logging of all artifact downloads
- Real-time security scanning of downloaded artifacts

**`TransferListener`** -- receives `transferCorrupted` events (checksum mismatch detected),
enabling custom alerting on integrity failures.

---

## 4. settings.xml as a Security Control Plane

### 4.1 Mirror Enforcement

The `<mirrors>` section redirects repository requests, enabling enterprise control over
all artifact sources:

```xml
<mirrors>
  <!-- Force ALL repositories through internal proxy -->
  <mirror>
    <id>corp-nexus</id>
    <name>Corporate Nexus</name>
    <url>https://nexus.corp.com/repository/maven-public/</url>
    <mirrorOf>*</mirrorOf>
  </mirror>
</mirrors>
```

**`mirrorOf` syntax:**

| Pattern | Meaning |
|---------|---------|
| `*` | Mirror ALL repositories |
| `central` | Only mirror Maven Central |
| `external:*` | All except localhost/file-based repos |
| `external:http:*` | All HTTP (non-HTTPS) external repos (Maven 3.8.0+) |
| `*,!repo1` | All except repo1 |

With `mirrorOf=*`, every artifact request goes through the corporate repository manager,
which can then enforce virus scanning, license compliance, vulnerability scanning, audit
logging, and artifact allowlisting/blocklisting.

### 4.2 Server Credentials

```xml
<servers>
  <server>
    <id>corp-nexus</id>                          <!-- matches mirror/repo id -->
    <username>build-agent</username>
    <password>{COQLCE6DU6GtcS5P=}</password>     <!-- encrypted -->
    <privateKey>${user.home}/.ssh/id_rsa</privateKey>  <!-- SSH key auth -->
    <passphrase>some_passphrase</passphrase>
    <filePermissions>664</filePermissions>
    <directoryPermissions>775</directoryPermissions>
  </server>
</servers>
```

Credentials are centrally managed in `settings.xml`, never in project files. The `<id>`
matches the repository or mirror `<id>`, so a single `settings.xml` secures all projects.

### 4.3 Password Encryption

**Maven 3** uses a master password stored in `~/.m2/settings-security.xml`:

```bash
mvn --encrypt-master-password    # creates master password
mvn --encrypt-password           # encrypts a server password using master
```

**Security caveat:** Maven 3's master password is encrypted with a hardcoded key. It
should be treated as equivalent to plaintext -- anyone with file access can decrypt it.
It is defense against shoulder-surfing, not against system compromise.

**Maven 4** introduces pluggable dispatchers with real cryptographic security:

| Dispatcher | Cipher | Key Source |
|-----------|--------|------------|
| Master (default) | `AES/GCM/NoPadding` + `PBKDF2WithHmacSHA512` | Multiple sources |
| Master Source Lookup | References external secret | External system |
| Legacy | Maven 3 compatibility | Hardcoded key |

Maven 4 master key sources:
1. Plain file: `file:<absolute-filepath>`
2. Environment variable: `env:<variable-name>`
3. Java system property: `system-property:<property-name>`
4. GnuPG Agent: `gpg-agent:<socketPath>`
5. Pinentry: `pinentry-prompt:<binary-path>`
6. 1Password CLI: `onepassword:<1Password Secret Reference URI>` (starts with `op://`)

### 4.4 Proxy Configuration

```xml
<proxies>
  <proxy>
    <id>corporate-proxy</id>
    <active>true</active>
    <protocol>https</protocol>
    <host>proxy.corp.com</host>
    <port>8080</port>
    <username>proxyuser</username>
    <password>{encrypted-password}</password>
    <nonProxyHosts>*.corp.com|localhost|127.0.0.1</nonProxyHosts>
  </proxy>
</proxies>
```

Combined with Apache HttpClient's proper TLS handling, proxy connections have correct
hostname verification -- the core security fix that motivated this work.

### 4.5 Profile-Based Repository Configuration

```xml
<profiles>
  <profile>
    <id>production</id>
    <repositories>
      <repository>
        <id>releases</id>
        <url>https://nexus.corp.com/repository/releases/</url>
        <releases>
          <enabled>true</enabled>
          <checksumPolicy>fail</checksumPolicy>
          <updatePolicy>daily</updatePolicy>
        </releases>
        <snapshots><enabled>false</enabled></snapshots>
      </repository>
    </repositories>
  </profile>
</profiles>
<activeProfiles>
  <activeProfile>production</activeProfile>
</activeProfiles>
```

Profiles enable environment-specific configurations (dev/staging/prod) without modifying
build files. Activation can be based on JDK version, OS, properties, or file existence.

### 4.6 Enterprise settings.xml Example

A complete compliance-focused configuration:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <!-- Route ALL artifact downloads through corporate repository manager -->
  <mirrors>
    <mirror>
      <id>corp-nexus</id>
      <url>https://nexus.corp.com/repository/maven-public/</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>

  <!-- Authenticated access -->
  <servers>
    <server>
      <id>corp-nexus</id>
      <username>${env.NEXUS_USERNAME}</username>
      <password>{encrypted-token}</password>
    </server>
  </servers>

  <!-- Corporate proxy with authentication -->
  <proxies>
    <proxy>
      <id>corporate-proxy</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>proxy.corp.com</host>
      <port>8080</port>
      <username>${env.PROXY_USER}</username>
      <password>{encrypted-proxy-password}</password>
      <nonProxyHosts>*.corp.com|localhost|nexus.corp.com</nonProxyHosts>
    </proxy>
  </proxies>

  <!-- Strict release-only profile -->
  <profiles>
    <profile>
      <id>production</id>
      <repositories>
        <repository>
          <id>corp-releases</id>
          <url>https://nexus.corp.com/repository/releases/</url>
          <releases>
            <enabled>true</enabled>
            <checksumPolicy>fail</checksumPolicy>
            <updatePolicy>daily</updatePolicy>
          </releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>production</activeProfile>
  </activeProfiles>
</settings>
```

### 4.7 What settings.xml Enables That Coursier Cannot

| Capability | settings.xml + Maven Resolver | Coursier |
|---|---|---|
| Proper TLS hostname verification through proxies | Yes (Apache HttpClient) | No (OkHttp bug, "won't fix") |
| Standard enterprise credential format | Yes (same as Maven/Gradle) | Non-standard `COURSIER_CREDENTIALS` |
| Encrypted passwords | Yes (`settings-security.xml`) | No |
| Mirror with wildcard routing | Yes (standard `mirrorOf` syntax) | Partial (own format) |
| Proxy with authenticated HTTPS | Yes (standard format, proper TLS) | Yes (but with TLS bug) |
| Per-repository checksum policy | Yes (`fail`/`warn`/`ignore`) | No (global only, accepts missing) |
| Repository manager integration | Native (standard `settings.xml`) | Requires custom config |
| Auditable by security teams | Yes (known XML schema, standard tooling) | No (non-standard) |

---

## 5. Broader Artifact Resolution Security Landscape

### 5.1 Supply Chain Attacks: Real-World Incidents

The threat landscape for artifact resolution has escalated dramatically:

**Dependency Confusion (2021):** Security researcher Alex Birsan demonstrated that package
managers (npm, pip, RubyGems) will prefer public packages with higher version numbers
over private internal packages. He successfully compromised Apple, Microsoft, PayPal,
Shopify, Netflix, Tesla, Uber, and Yelp, earning over $130,000 in bug bounties. Maven's
two-part coordinate system (`groupId:artifactId`) provides some namespace protection, but
organizations using custom groupIds not reserved on Maven Central remain vulnerable if
Maven Central is in their repository list.

**Typosquatting:** Sonatype reported multiple instances of typosquatted packages on Maven
Central in 2021-2023, including packages mimicking `com.google.guava` with slightly
different groupIds. The npm ecosystem is far more affected (700+ malicious packages in a
single 2023 campaign).

**Account Takeover:**
- `ua-parser-js` (October 2021) -- compromised npm account, 8M weekly downloads, published
  cryptomining and password-stealing malware
- `coa` and `rc` (November 2021) -- hijacked npm accounts (9M and 14M weekly downloads)
- Maven Central is somewhat resistant because Sonatype OSSRH requires validated namespace
  ownership and PGP signing, but a compromised account could still publish malicious artifacts

**Compromised Build Infrastructure:**
- **SolarWinds/SUNBURST (December 2020):** Attackers compromised the build pipeline itself
  and injected a backdoor into the Orion software update, affecting ~18,000 organizations
  including US government agencies
- **Codecov (April 2021):** Modified Bash Uploader script in CI pipelines of thousands of
  organizations, exfiltrating environment variables including CI secrets
- **GitHub Actions (2022-2025):** Multiple incidents where popular GitHub Actions were
  compromised, including `tj-actions/changed-files` (March 2025) which exposed CI secrets
  from 23,000+ repositories

**XZ Utils Backdoor (CVE-2024-3094, March 2024):** The most sophisticated supply chain
attack ever discovered. A contributor using the pseudonym "Jia Tan" spent approximately
two years building trust, then injected obfuscated malicious code hidden in binary test
fixtures. The backdoor targeted OpenSSH authentication via the dependency chain
(liblzma -> libsystemd -> sshd), allowing unauthorized remote code execution. Key
implications for build systems:

- Build inputs from trusted repositories can be compromised through social engineering
- Binary test data is an attack vector -- build systems should be suspicious of binary blobs
- The backdoor was only in tarball releases, not git source -- highlighting the importance
  of reproducible builds
- No automated tool would have caught this; it required human review
- Transitive dependency risk: most users of xz/liblzma had no idea they depended on it

### 5.2 Gradle's Security Model (The Gold Standard)

Gradle introduced dependency verification in version 6.2 (February 2020) with
`gradle/verification-metadata.xml`. It is currently the most comprehensive dependency
verification system among JVM build tools.

**Structure:**
```xml
<verification-metadata>
  <configuration>
    <verify-metadata>true</verify-metadata>
    <verify-signatures>true</verify-signatures>
    <trusted-keys>
      <trusted-key id="ABCDEF0123456789" group="org.apache"/>
    </trusted-keys>
  </configuration>
  <components>
    <component group="com.google.guava" name="guava" version="31.1-jre">
      <artifact name="guava-31.1-jre.jar">
        <sha256 value="a42edc9cab..." origin="Generated by Gradle"/>
        <pgp value="B5017F2E..."/>
      </artifact>
    </component>
  </components>
</verification-metadata>
```

**Key features:**
- **Dual verification:** checksums AND PGP signatures simultaneously
- **Trusted keys:** fine-grained PGP key trust per group, artifact, or version
- **Generation command:** `gradle --write-verification-metadata sha256,pgp dependencies`
- **Dependency locking:** separate `*.lockfile` files recording exact resolved versions
- **Configurable strictness:** missing checksums fail by default; missing signatures configurable

**What Gradle does that neither Maven Resolver nor Coursier does:**
- Built-in PGP signature verification during resolution
- Combined checksum + signature verification in a single configuration file
- First-class dependency locking with lock files

### 5.3 Maven's Own Security Gaps

Even with Maven Resolver, Maven has significant security gaps:

**No PGP verification by default:** Maven downloads `.asc` signature files from Maven
Central but **never verifies them**. The signatures are effectively decorative for
consumers. The `maven-gpg-plugin` handles signing for publishers, not verification for
consumers.

**"Trusts the uploader, not the artifact" model:** Maven Central's security is
identity-based. If you can authenticate as the namespace owner and provide valid PGP
signatures, artifacts are accepted. There is:
- No binary content scanning for malware
- No code review of published artifacts
- No mechanism to verify artifacts were built from specific source code
- No build provenance requirement

**SNAPSHOT dependencies are inherently insecure:**
- Mutable by design -- can be republished with different content at any time
- No content verification mechanism
- Timestamp-based versioning with no audit trail

**Repository injection via POM:** Maven POMs can declare `<repositories>` elements that
add new repositories to the resolution process. A transitive dependency's POM could point
to a malicious repository. The `requireNoRepositories` enforcer rule blocks this but is
not enabled by default.

**Maven Enforcer Plugin limitations:** The enforcer provides policy rules (`bannedDependencies`,
`requireReleaseDeps`, `dependencyConvergence`, etc.) but operates on metadata, not artifact
content. It does not verify checksums, signatures, or artifact integrity.

### 5.4 Sigstore and Modern Signing

Sigstore (part of the OpenSSF) represents the future of artifact signing:

**How it improves on PGP:**
- **Keyless signing** -- no long-lived keys to manage or compromise
- **Identity-based** -- signing tied to verified identities (OIDC tokens from GitHub, Google)
- **Transparency log** -- all signatures recorded in Rekor (immutable, append-only, auditable)
- **Short-lived certificates** -- valid for minutes, reducing key compromise window
- **No key distribution problem** -- eliminates PGP's web-of-trust complexity

**Current state in the Java ecosystem:**
- Maven Central supports Sigstore signing as supplementary to PGP (PGP still required)
- `sigstore-maven-plugin` available for publishers
- Consumer-side verification not yet integrated into `mvn` CLI or any build tool natively
- npm has adopted Sigstore for provenance attestations since npm 9.5.0 (March 2023)
- Go toolchain uses Sigstore for the module checksum database

**Maven Resolver has built-in Sigstore signing support** (`aether.generator.sigstore.enabled`),
but only for the publishing side. Verification is not yet available.

### 5.5 SLSA (Supply-chain Levels for Software Artifacts)

SLSA is an OpenSSF framework for supply chain integrity:

| Level | Requirements |
|-------|-------------|
| SLSA 1 | Build process documented; provenance generated (not necessarily unforgeable) |
| SLSA 2 | Build service authenticated; provenance tamper-resistant |
| SLSA 3 | Build platform hardened; provenance non-forgeable (isolated, ephemeral builds) |

**Current compliance:**
- Maven Central: does not require or store SLSA provenance attestations
- npm: supports provenance attestations via Sigstore (SLSA Build Level 3 for GitHub Actions)
- GitHub: provides SLSA Build Level 3 provenance via `slsa-github-generator`
- Mill: no SLSA support currently

### 5.6 Software Bill of Materials (SBOM)

SBOMs are increasingly mandated by regulation:

- **US Executive Order 14028 (2021):** SBOMs required for software sold to the federal government
- **EU Cyber Resilience Act (2024):** SBOMs required for products with digital elements
  sold in the EU, enforceable by 2027
- **FDA guidance (2023):** SBOMs required for medical device software

**Formats:** CycloneDX (OWASP, security-focused) and SPDX (Linux Foundation, ISO standard).

**Mill's current state:** The `contrib/sbom` module (`CycloneDXJavaModule`) generates
CycloneDX SBOMs from resolved dependencies. This is a solid foundation but limited to
the contrib module.

### 5.7 Repository Manager Security Features

Enterprise repository managers (Nexus, Artifactory) provide server-side enforcement that
complements `settings.xml` mirror configuration:

| Feature | Sonatype Nexus | JFrog Artifactory |
|---------|---------------|-------------------|
| Vulnerability scanning | Nexus IQ / Lifecycle | JFrog Xray |
| License compliance | Yes | Yes |
| Automatic quarantine | Yes | Yes |
| Content validation | Yes | Yes |
| Namespace/group validation | Yes | Yes |
| Immutable releases | Yes | Yes |
| Audit logging | Yes | Yes |
| LDAP/SSO integration | Yes | Yes |
| Checksum-based storage | Yes | Yes (deduplication) |

The key insight: `settings.xml`'s `mirrorOf=*` routes all traffic through the repository
manager, which applies these policies server-side. This is defense-in-depth -- even if the
build tool has gaps, the repository manager catches issues.

---

## 6. Comparison Matrix

### Coursier vs Maven Resolver vs Gradle

| Security Feature | Coursier | Maven Resolver | Gradle |
|---|---|---|---|
| **Transport Security** | | | |
| TLS hostname verification through proxies | **Broken** (OkHttp) | **Correct** (Apache HttpClient) | **Correct** |
| TLS protocol/cipher configuration | No | Yes (`aether.transport.apache.https.*`) | Yes |
| Proxy authentication | Yes (non-standard format) | Yes (`settings.xml`) | Yes (`gradle.properties`) |
| Certificate pinning | No | Via Java keystore | Via Java keystore |
| **Integrity Verification** | | | |
| Checksum verification | SHA-1, SHA-256, MD5 | SHA-1, SHA-256, SHA-512, MD5 | SHA-1, SHA-256, SHA-512, MD5 |
| Missing checksum behavior | **Always accepts** | Configurable: fail/warn/ignore | **Fails by default** |
| Invalid checksum behavior | Warns | Configurable: fail/warn/ignore | Fails |
| **Authenticity Verification** | | | |
| PGP signature verification | **No** | **No** (plugin available) | **Yes** (built-in) |
| Sigstore verification | No | No (signing only) | No (plugin available) |
| Trusted keys configuration | No | No | Yes |
| **Dependency Verification** | | | |
| Verification metadata file | **No** | Yes (trusted checksums) | Yes (`verification-metadata.xml`) |
| Dependency locking | No | Partial (enforcer rules) | Yes (lock files) |
| Repository filtering | No | Yes (groupId + prefix filters) | No |
| **Credential Management** | | | |
| Standard enterprise format | No (`COURSIER_CREDENTIALS`) | Yes (`settings.xml`) | Yes (`gradle.properties`) |
| Encrypted passwords | No | Yes (`settings-security.xml`) | Yes (credential store plugins) |
| External secret managers | No | Yes (Maven 4: 1Password, gpg-agent, env vars) | Yes (plugins) |
| **Repository Control** | | | |
| Mirror enforcement | Partial (non-standard) | Yes (`mirrorOf` wildcards) | Yes |
| Repository manager integration | Manual | Native (`settings.xml`) | Native |
| SNAPSHOT policy control | No | Yes (profile-level enable/disable) | Yes |
| **Audit & Compliance** | | | |
| SBOM generation | Via Mill contrib | Via plugins | Via plugins |
| Resolution event listeners | No | Yes (`RepositoryListener`, `TransferListener`) | Yes (build scan) |
| Configuration auditable by security teams | No | Yes (standard XML schema) | Partially (Groovy/Kotlin DSL) |

---

## 7. What's Still Lacking (Even with Maven Resolver)

### 7.1 No PGP Signature Verification on Downloaded Artifacts

**This is the single largest gap.** Neither Maven Resolver nor Coursier verifies PGP
signatures on downloaded artifacts. Gradle is the only JVM build tool that does this
natively.

**What exists in the Maven ecosystem:**
- The `pgpverify-maven-plugin` (s4u/simplify4u) can verify PGP signatures as a build
  lifecycle plugin. It downloads `.asc` files, fetches public keys from keyservers, and
  verifies signatures. It supports a `keysMap` file to pin expected signing keys per
  artifact. However, it is a Maven build plugin, not a resolver feature -- integrating it
  into Mill would require custom development.

**Why this matters:**
- Checksums verify integrity (transport corruption) but not authenticity (who published it)
- A compromised repository mirror could serve modified artifacts with matching checksums
- PGP/Sigstore verification confirms the artifact was signed by the expected publisher

**Mitigation:** Repository managers (Nexus, Artifactory) can verify signatures server-side.
With `mirrorOf=*`, all artifacts go through the repository manager, which can enforce
signature policies.

### 7.2 No Built-in Dependency Locking

Maven Resolver's trusted checksums provide content verification (the artifact bytes match
a known-good checksum) but not version locking (ensuring the same version is resolved
every time). A `commons-lang3:3.12.0` today and `commons-lang3:3.12.0` tomorrow should
have the same checksum, but there is no mechanism to prevent version drift in transitive
dependencies that use ranges.

**Comparison:**
- Gradle has `dependencyLocking { lockAllConfigurations() }` with `.lockfile` files
- npm has `package-lock.json`
- Maven has no built-in lock file (the Versions Maven Plugin can rewrite POMs to concrete
  versions, which is a workaround)

### 7.3 No Reproducible Build Verification

Maven Resolver cannot verify that a downloaded artifact was built from specific source
code. Even with checksums and signatures, a legitimately signed artifact could contain
a backdoor (as demonstrated by the XZ Utils incident).

**Reproducible builds** are the only defense: building from source and comparing the output
bit-for-bit against the published artifact. As of 2024, approximately 30% of Maven Central
artifacts are reproducible (up from ~10% in 2021), supported by `project.build.outputTimestamp`
in POMs.

### 7.4 No SLSA Provenance Verification

Neither Maven Resolver nor any JVM build tool verifies SLSA provenance attestations during
dependency resolution. This means there is no way to verify:
- WHERE an artifact was built (which CI system)
- FROM what source code
- BY what build process

This is an emerging standard -- npm is ahead here with Sigstore-based provenance -- but
it will become increasingly important as regulations (EU CRA, US EO 14028) mature.

### 7.5 No Runtime Vulnerability Scanning

Maven Resolver resolves and downloads artifacts but does not check them against
vulnerability databases (NVD, OSV, GitHub Advisory Database). This is typically handled
by:
- Repository managers (Nexus IQ, JFrog Xray) -- server-side scanning
- Build plugins (OWASP Dependency-Check, Snyk)
- CI integrations (GitHub Dependabot, Renovate)

### 7.6 SNAPSHOT Security Remains Problematic

Even with Maven Resolver, SNAPSHOT dependencies are inherently mutable and lack content
verification. A compromised SNAPSHOT repository can serve different content on each request.
The best mitigation is disabling SNAPSHOTs entirely for production builds
(`<snapshots><enabled>false</enabled></snapshots>`).

### 7.7 Maven 3 Password Encryption is Weak

Maven 3's `settings-security.xml` uses a hardcoded key for the master password. Anyone
with file access can decrypt all server passwords. This is widely known but often overlooked.
Maven 4 fixes this with proper cryptographic key sources, but Maven 4 is not yet widely
adopted.

For Phase 1, Mill should document this limitation and recommend:
- Using Maven 4's encryption when available
- Using environment variables for CI credentials
- Using repository manager tokens (which can be rotated) rather than long-lived passwords

---

## 8. Phase 1 Security Feature Assessment

### 8.1 What We Get For Free (Zero Custom Code Beyond settings.xml Loading)

These security features are inherent in using Maven Resolver + `settings.xml`:

| Feature | How | Lines of Code |
|---------|-----|---------------|
| Proper TLS hostname verification through proxies | Apache HttpClient does this by default | 0 |
| Mirror enforcement (all traffic through corporate repo) | `MirrorSelector` from `settings.xml` | 0 (part of settings.xml loading) |
| Server credentials for private repos | `AuthenticationSelector` from `settings.xml` | 0 (part of settings.xml loading) |
| Proxy configuration with authentication | `ProxySelector` from `settings.xml` | 0 (part of settings.xml loading) |
| Per-repository checksum policies | Configured in `settings.xml` repository definitions | 0 |
| Standard enterprise configuration format | `settings.xml` is the standard | 0 |
| Security auditability | Known XML schema, standard tooling | 0 |

### 8.2 What Requires Minimal Effort (Configuration Properties)

| Feature | Effort | Implementation |
|---------|--------|----------------|
| Global strict checksum policy | ~1 line | `session.setChecksumPolicy("fail")` |
| Strong checksum algorithms | ~1 line | `session.setConfigProperty("aether.checksums.checksumAlgorithms", "SHA-256,SHA-1")` |
| TLS protocol/cipher restriction | ~2 lines | `session.setConfigProperty("aether.transport.apache.https.protocols", ...)` |
| Offline mode integration | ~1 line | `session.setOffline(true)` when Mill `--offline` |
| Encrypted password support | ~20 lines | Add `maven-settings-crypto` to worker classpath, decrypt settings |

### 8.3 What Requires Moderate Effort

| Feature | Effort | Notes |
|---------|--------|-------|
| Trusted checksums (dependency verification) | ~50 lines | Session config properties + need a Mill command for record/verify modes |
| Remote repository filtering (groupId/prefix) | ~30 lines | Session config properties + filter files |
| Audit logging via RepositoryListener | ~40 lines | Custom listener implementation |

### 8.4 What's Out of Scope for Phase 1

| Feature | Why Defer | Future Effort |
|---------|-----------|---------------|
| PGP signature verification | Needs key management infrastructure and trust policy design | High (weeks) |
| Sigstore verification | Consumer-side verification not yet available in Maven Resolver | Blocked on upstream |
| Dependency locking command | Needs UX design, integration with trusted checksums | Moderate (days) |
| SBOM generation during resolution | Post-resolution feature, needs format integration | Moderate (days) |
| CVE checking integration | Better done via repository manager (Nexus/Artifactory) | High (weeks) |
| SLSA provenance verification | Emerging standard, no resolver support yet | Blocked on ecosystem |
| Reproducible build verification | Requires infrastructure beyond the resolver | High (months) |

### 8.5 Recommended Phase 1 Session Configuration

```java
public DefaultRepositorySystemSession createSecureSession(
    Settings settings,
    LocalRepository localRepo
) {
    RepositorySystem system = new RepositorySystemSupplier().get();
    DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();

    // Local repository
    session.setLocalRepositoryManager(
        system.newLocalRepositoryManager(session, localRepo));

    // 1. Apply settings.xml security selectors
    applyMirrors(session, settings);       // mirror enforcement
    applyAuthentication(session, settings); // server credentials
    applyProxies(session, settings);       // proxy + TLS

    // 2. Strict checksum policy (default "warn" for compat, "fail" recommended)
    session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_WARN);

    // 3. Strong checksum algorithms
    session.setConfigProperty(
        "aether.checksums.checksumAlgorithms", "SHA-256,SHA-1,MD5");

    // 4. TLS hardening
    session.setConfigProperty(
        "aether.transport.apache.https.protocols", "TLSv1.2,TLSv1.3");

    // 5. Make session immutable after configuration
    session.setReadOnly();

    return session;
}
```

### 8.6 Security Properties of Phase 1 vs Coursier

| Threat | Coursier (current) | Phase 1 Maven Backend |
|--------|-------------------|----------------------|
| MITM via corporate proxy | **Vulnerable** (OkHttp hostname bug) | **Protected** (Apache HttpClient) |
| Artifact substitution on unauthenticated repo | Warns on invalid checksum, **accepts missing** | Configurable `fail` policy, **rejects missing** |
| Dependency confusion | No defense | Repository filtering (groupId/prefix) |
| Credential theft from config files | Plaintext credentials | Encrypted passwords (`settings-security.xml`) |
| Unapproved repository usage | Manual configuration only | Mirror enforcement (`mirrorOf=*`) |
| Artifact content drift between builds | No defense | Trusted checksums (post-resolution verification) |
| Non-compliant with enterprise security audit | Non-standard formats | Standard `settings.xml` (known, auditable) |
| PGP signature verification | **Not available** | **Not available** (same gap) |

---

## 9. Recommendations and Future Work

### 9.1 Phase 1 Must-Have Security Features

1. **settings.xml loading** -- mirrors, servers, proxies (already in the design)
2. **Configurable checksum policy** -- expose `checksumPolicy` as a Mill configuration
   option, defaulting to `warn` for compatibility but documented as `fail` for compliance
3. **SHA-256 as default checksum algorithm** -- stronger than the current SHA-1/MD5 default
4. **TLS 1.2+ enforcement** -- reject older TLS versions
5. **System JVM enforcement** -- when Maven backend is active, disable Coursier JVM downloads

### 9.2 Phase 1 Should-Have Security Features

1. **Trusted checksums integration** -- expose Maven Resolver's trusted checksums as a
   Mill command (`mill verifyChecksums --record` / `mill verifyChecksums --check`)
2. **Repository filtering** -- expose groupId/prefix filtering for dependency confusion defense
3. **Encrypted password support** -- decrypt `settings-security.xml` passwords in the worker
4. **Audit logging** -- log resolved artifacts via `RepositoryListener` when verbose mode is on

### 9.3 Future Security Roadmap (Post Phase 1)

**Short-term (Phase 2):**
- PGP signature verification using Bouncy Castle (Mill already depends on `bcpg-jdk18on:1.83`)
- Dependency locking command with lock file generation
- Integration with Mill's existing SBOM support (`contrib/sbom`)

**Medium-term:**
- Sigstore verification (when consumer-side support matures in Maven Resolver)
- SLSA provenance verification
- Vulnerability scanning integration (OWASP Dependency-Check or similar)

**Long-term:**
- Reproducible build verification for dependencies
- Full type abstraction (Phase 2 from the design exploration report) to remove Coursier
  types from the classpath entirely when Maven backend is active

### 9.4 Existing Mill Security Assets

Mill already has several security-relevant capabilities that complement this work:

| Asset | Location | Relevance |
|-------|----------|-----------|
| PGP signing for publishing | `libs/javalib/src/mill/javalib/PgpWorkerSupport.scala` | Uses Bouncy Castle, could be extended for verification |
| SBOM generation | `contrib/sbom/src/mill/contrib/sbom/CycloneDXJavaModule.scala` | CycloneDX format, could integrate with resolution metadata |
| Maven Resolver for publishing | `libs/javalib/maven-worker/` | Established worker pattern, reusable infrastructure |
| Maven Resolver for POM reading | `libs/init/maven/` | Session setup patterns, `settings.xml` loading |
| Checksum generation for publishing | `libs/javalib/src/mill/javalib/publish/SonatypeHelpers.scala` | MD5/SHA1 generation (should add SHA-256) |

### 9.5 Version Alignment Note

The current codebase has mixed Maven Resolver versions:
- `libs/javalib/maven-worker/`: Maven Resolver 2.0.10 (main), but `supplier:2.0.0-alpha-8`
  and `transport-http:2.0.0-alpha-2` (alpha)
- `libs/init/maven/`: Maven Resolver 1.9.22 (stable, older)

Phase 1 should align on stable 2.0.x releases for all Maven Resolver components in the
new resolver worker module. The mixed alpha versions in the existing publishing worker
should also be updated as a follow-up.

---

## Appendix A: CVE References

| CVE | Description | Impact on Mill |
|-----|-------------|---------------|
| CVE-2022-46751 | XML External Entity injection in Apache XML libraries | Coursier dependency; mitigated by updating to Coursier 2.1.6 (Mill PR #2705) |
| CVE-2022-37866 | Path traversal in artifact handling | Coursier dependency; mitigated by hardening Maven/Ivy artifact handling (GHSA-wv7w-rj2x-556x) |
| CVE-2024-3094 | XZ Utils backdoor | Not directly applicable, but demonstrates sophistication of supply chain attacks |
| (No CVE) | OkHttp hostname verification through proxy | Active vulnerability in Coursier; primary motivation for Maven Resolver integration |

## Appendix B: Relevant Standards and Frameworks

| Standard | Organization | Relevance |
|----------|-------------|-----------|
| SLSA | OpenSSF | Supply chain integrity levels; build provenance |
| Sigstore | OpenSSF | Keyless artifact signing and transparency logs |
| CycloneDX | OWASP | SBOM format for security use cases |
| SPDX | Linux Foundation | SBOM format (ISO/IEC 5962:2021) |
| EU Cyber Resilience Act | European Commission | SBOM requirement for products sold in EU (2027) |
| US Executive Order 14028 | White House | SBOM requirement for federal government software |
| OpenSSF Scorecard | OpenSSF | Automated security assessment for open-source projects |

## Appendix C: Key Maven Resolver Configuration Properties

Security-relevant session configuration properties:

```properties
# Checksum verification
aether.checksums.checksumAlgorithms=SHA-256,SHA-1,MD5
aether.connector.basic.smartChecksums=true

# Trusted checksums (dependency verification)
aether.artifactResolver.postProcessor.trustedChecksums=true
aether.artifactResolver.postProcessor.trustedChecksums.failIfMissing=true
aether.artifactResolver.postProcessor.trustedChecksums.checksumAlgorithms=SHA-256
aether.artifactResolver.postProcessor.trustedChecksums.record=false
aether.trustedChecksumsSource.summaryFile=true
aether.trustedChecksumsSource.summaryFile.basedir=${session.rootDirectory}/.mvn/checksums

# Repository filtering (dependency confusion defense)
aether.remoteRepositoryFilter.groupId=true
aether.remoteRepositoryFilter.groupId.basedir=${session.rootDirectory}/.mvn/rrf/
aether.remoteRepositoryFilter.prefixes=true

# Transport security
aether.transport.apache.https.protocols=TLSv1.2,TLSv1.3
aether.transport.apache.https.cipherSuites=<comma-separated strong ciphers>
aether.transport.http.connectTimeout=30000
aether.transport.http.requestTimeout=300000

# Signing (publishing side)
aether.generator.gpg.enabled=true
aether.generator.sigstore.enabled=true
```
