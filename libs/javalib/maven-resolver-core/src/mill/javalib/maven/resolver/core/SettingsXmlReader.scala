package mill.javalib.maven.resolver.core

import org.apache.maven.settings.Settings
import org.apache.maven.settings.building.{
  DefaultSettingsBuilderFactory,
  DefaultSettingsBuildingRequest
}
import org.codehaus.plexus.components.secdispatcher.SecDispatcher
import org.codehaus.plexus.components.secdispatcher.internal.DefaultSecDispatcher
import org.codehaus.plexus.components.secdispatcher.internal.dispatchers.LegacyDispatcher

import java.io.File
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Reads Maven settings.xml files, decrypts encrypted passwords, and
 * extracts mirror/server/proxy/repository configuration.
 *
 * Supports both Maven 3 encrypted passwords (`{...}` format via
 * `settings-security.xml`) and Maven 4 pluggable dispatchers.
 *
 * Lives in `maven-resolver-core` so both the worker and launcher can use it.
 */
object SettingsXmlReader {

  /** Mirror configuration extracted from `<mirrors>`. */
  case class MirrorConfig(
      id: String,
      url: String,
      mirrorOf: String,
      layout: Option[String],
      blocked: Boolean
  )

  /** Server credentials extracted from `<servers>`, with passwords decrypted. */
  case class ServerConfig(
      id: String,
      username: Option[String],
      password: Option[String],
      privateKey: Option[String],
      passphrase: Option[String]
  )

  /** Proxy configuration extracted from `<proxies>` (active only). */
  case class ProxyConfig(
      id: String,
      protocol: String,
      host: String,
      port: Int,
      username: Option[String],
      password: Option[String],
      nonProxyHosts: Option[String]
  )

  /** Repository from active `<profile>` entries. */
  case class RepositoryConfig(
      id: String,
      url: String,
      layout: String
  )

  /** All settings extracted from one or more settings.xml files. */
  case class EffectiveSettings(
      mirrors: Seq[MirrorConfig],
      servers: Map[String, ServerConfig],
      proxies: Seq[ProxyConfig],
      repositories: Seq[RepositoryConfig]
  )

  /**
   * Load settings.xml files, decrypt passwords, and extract all configuration.
   *
   * Missing files are handled gracefully — an empty `EffectiveSettings` is returned.
   */
  def load(
      userSettingsPath: Option[String] = None,
      globalSettingsPath: Option[String] = None
  ): EffectiveSettings = {
    val settings = loadRawSettings(userSettingsPath, globalSettingsPath)
    val decrypted = decryptPasswords(settings)
    extract(decrypted)
  }

  /**
   * Load and merge user/global settings.xml into effective settings.
   *
   * Returns the raw `Settings` object with passwords still potentially encrypted.
   */
  def loadRawSettings(
      userSettingsPath: Option[String] = None,
      globalSettingsPath: Option[String] = None
  ): Settings = {
    val request = DefaultSettingsBuildingRequest()
    userSettingsPath.foreach(p => request.setUserSettingsFile(File(p)))
    globalSettingsPath.foreach(p => request.setGlobalSettingsFile(File(p)))
    request.setSystemProperties(System.getProperties)

    val builder = DefaultSettingsBuilderFactory().newInstance()
    builder.build(request).getEffectiveSettings
  }

  /**
   * Decrypt encrypted passwords in server entries.
   *
   * Supports Maven 3 format (`{...}` via `settings-security.xml` with
   * hardcoded key) and Maven 4 pluggable dispatchers. Plain-text passwords
   * pass through unchanged.
   *
   * Decryption failures are silently ignored — the original encrypted string
   * is preserved, and resolution will fail later with a clear auth error
   * rather than crashing here.
   */
  def decryptPasswords(settings: Settings): Settings =
    decryptPasswords(settings, createSecDispatcher())

  /**
   * Decrypt encrypted passwords in server entries using the given dispatcher.
   *
   * Visible to tests so they can supply a custom dispatcher with a known
   * master password, since `LegacyDispatcher.encrypt()` is blocked in 4.x.
   */
  private[core] def decryptPasswords(
      settings: Settings,
      dispatcher: SecDispatcher
  ): Settings = {
    settings.getServers.asScala.foreach { server =>
      Option(server.getPassword).foreach { pwd =>
        if (dispatcher.isLegacyEncryptedString(pwd) || dispatcher.isEncryptedString(pwd)) {
          Try(dispatcher.decrypt(pwd)).foreach(server.setPassword)
        }
      }
      Option(server.getPassphrase).foreach { phrase =>
        if (dispatcher.isLegacyEncryptedString(phrase) || dispatcher.isEncryptedString(phrase)) {
          Try(dispatcher.decrypt(phrase)).foreach(server.setPassphrase)
        }
      }
    }
    settings
  }

  /** Extract typed settings from a (possibly already-decrypted) `Settings` object. */
  def extract(settings: Settings): EffectiveSettings =
    EffectiveSettings(
      mirrors = extractMirrors(settings),
      servers = extractServers(settings),
      proxies = extractProxies(settings),
      repositories = extractProfileRepositories(settings)
    )

  def extractMirrors(settings: Settings): Seq[MirrorConfig] =
    settings.getMirrors.asScala.toSeq.map { m =>
      MirrorConfig(
        id = m.getId,
        url = m.getUrl,
        mirrorOf = m.getMirrorOf,
        layout = Option(m.getLayout),
        blocked = m.isBlocked
      )
    }

  def extractServers(settings: Settings): Map[String, ServerConfig] =
    settings.getServers.asScala.map { s =>
      s.getId -> ServerConfig(
        id = s.getId,
        username = Option(s.getUsername),
        password = Option(s.getPassword),
        privateKey = Option(s.getPrivateKey),
        passphrase = Option(s.getPassphrase)
      )
    }.toMap

  def extractProxies(settings: Settings): Seq[ProxyConfig] =
    settings.getProxies.asScala.toSeq
      .filter(_.isActive)
      .map { p =>
        ProxyConfig(
          id = p.getId,
          protocol = p.getProtocol,
          host = p.getHost,
          port = p.getPort,
          username = Option(p.getUsername),
          password = Option(p.getPassword),
          nonProxyHosts = Option(p.getNonProxyHosts)
        )
      }

  /**
   * Extract repositories from active profiles in settings.xml.
   *
   * A profile is active if it is listed in `<activeProfiles>` or has
   * `<activation><activeByDefault>true</activeByDefault></activation>`.
   */
  def extractProfileRepositories(settings: Settings): Seq[RepositoryConfig] = {
    val activeProfileIds = settings.getActiveProfiles.asScala.toSet
    settings.getProfiles.asScala.toSeq
      .filter { p =>
        activeProfileIds.contains(p.getId) ||
        Option(p.getActivation).exists(_.isActiveByDefault)
      }
      .flatMap(_.getRepositories.asScala)
      .map { r =>
        RepositoryConfig(
          id = r.getId,
          url = r.getUrl,
          layout = Option(r.getLayout).getOrElse("default")
        )
      }
  }

  /**
   * Create a `SecDispatcher` that handles both Maven 3 legacy and Maven 4
   * encrypted passwords.
   *
   * The `LegacyDispatcher` reads `settings-security.xml` from
   * `System.getProperty("settings.security")` or `~/.m2/settings-security.xml`.
   */
  private[core] def createSecDispatcher(): SecDispatcher = {
    val dispatchers = Map[String, org.codehaus.plexus.components.secdispatcher.Dispatcher](
      LegacyDispatcher.NAME -> LegacyDispatcher()
    ).asJava
    // Configuration file path for Maven 4 settings-security4.xml
    val configPath = Paths.get(
      System.getProperty("user.home"),
      ".m2",
      "settings-security4.xml"
    )
    DefaultSecDispatcher(dispatchers, configPath)
  }
}
