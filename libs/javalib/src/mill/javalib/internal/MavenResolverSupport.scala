package mill.javalib.internal

import mill.api.daemon.internal.internal

/**
 * API boundary between Mill and the Maven Resolver worker classloader.
 *
 * All types are plain Scala case classes with String/Seq fields — no Coursier types,
 * no Maven Resolver types. They cross the classloader boundary via the shared parent
 * classloader.
 */
@internal
object MavenResolverSupport {

  /** API boundary between Mill and the Maven Resolver worker classloader. */
  trait Api extends AutoCloseable {

    /**
     * Initialize the worker with session configuration. Called once when the
     * worker is created. Loads settings.xml, configures security policies,
     * and creates the RepositorySystem + session.
     */
    def initialize(config: SessionConfig): Unit

    /**
     * Resolve dependencies and return paths to downloaded artifacts.
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
     */
    def resolveMetadata(
        deps: Seq[DependencyCoord],
        repositories: Seq[RepoCoord],
        forceVersions: Map[String, String],
        bomDeps: Seq[BomCoord]
    ): ResolutionMetadata

    /**
     * Return artifact metadata from the most recent resolution.
     * Enables downstream consumers (e.g. Sigstore verification) to access
     * repository URLs without re-resolving.
     */
    def lastResolvedArtifacts(): Seq[ResolvedArtifact]
  }

  // --- Session configuration (set once at initialization) ---

  case class SessionConfig(
      /** Path to local repository (default: ~/.m2/repository) */
      localRepositoryPath: String,
      /** Path to user settings.xml (default: ~/.m2/settings.xml) */
      userSettingsPath: Option[String] = None,
      /** Path to global settings.xml (default: /etc/maven/settings.xml) */
      globalSettingsPath: Option[String] = None,
      /** Checksum policy: "fail", "warn", "ignore" */
      checksumPolicy: String = "warn",
      /** Preferred checksum algorithms, comma-separated (default: SHA-256,SHA-1) */
      checksumAlgorithms: String = "SHA-256,SHA-1",
      /** Minimum TLS protocol versions, comma-separated */
      tlsProtocols: String = "TLSv1.2,TLSv1.3",
      /** Whether to operate in offline mode */
      offline: Boolean = false,
      /** Update policy for releases: "always", "daily", "interval:N", "never" */
      updatePolicy: String = "daily",
      /** Whether snapshot resolution is allowed */
      snapshotsEnabled: Boolean = true
  )

  // --- Serializable data types crossing the classloader boundary ---

  case class DependencyCoord(
      groupId: String,
      artifactId: String,
      version: String,
      configuration: String,
      exclusions: Seq[(String, String)],
      classifier: String,
      `type`: String
  )

  case class RepoCoord(
      id: String,
      url: String,
      `type`: String
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
      path: String,
      repositoryUrl: String
  )

  case class ResolutionMetadata(
      transitiveDeps: Map[String, Seq[ResolvedDep]],
      projectCache: Map[String, ProjectInfo],
      orderedDeps: Seq[ResolvedDep]
  )

  case class ResolvedDep(
      groupId: String,
      artifactId: String,
      version: String,
      configuration: String
  )

  case class ProjectInfo(
      overrides: Seq[((String, String, String, String), String)],
      licenses: Seq[(String, String)],
      parent: Option[(String, String, String)]
  )
}
