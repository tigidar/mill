package mill.javalib.maven.resolver.core

/**
 * Configuration for a Maven Resolver session.
 *
 * All fields are plain types with sensible defaults for security-hardened resolution.
 * The worker translates from its own `MavenResolverSupport.SessionConfig` to this type.
 */
case class SessionConfig(
    /** Path to local repository (default: ~/.m2/repository) */
    localRepositoryPath: String,
    /** Path to user settings.xml (default: ~/.m2/settings.xml) */
    userSettingsPath: Option[String] = None,
    /** Path to global settings.xml (default: /etc/maven/settings.xml) */
    globalSettingsPath: Option[String] = None,
    /** Checksum policy: "fail", "warn", "ignore" */
    checksumPolicy: String = "warn",
    /** Preferred checksum algorithms, comma-separated */
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
