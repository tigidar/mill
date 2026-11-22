package mill.javalib.maven.resolver.core

import org.apache.maven.settings.{Server, Settings}
import org.eclipse.aether.{
  ConfigurationProperties,
  DefaultRepositorySystemSession,
  RepositorySystem,
  RepositorySystemSession
}
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.DependencyFilter
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.resolution.{DependencyRequest, DependencyResult}
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.util.graph.manager.ClassicDependencyManager
import org.eclipse.aether.util.graph.selector.{
  AndDependencySelector,
  ExclusionDependencySelector,
  OptionalDependencySelector,
  ScopeDependencySelector
}
import org.eclipse.aether.util.graph.transformer.{
  ChainedDependencyGraphTransformer,
  ConflictResolver,
  JavaDependencyContextRefiner,
  JavaScopeDeriver,
  JavaScopeSelector,
  NearestVersionSelector,
  SimpleOptionalitySelector
}
import org.eclipse.aether.util.repository.{
  AuthenticationBuilder,
  DefaultMirrorSelector,
  DefaultProxySelector
}

import java.io.File
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*

/**
 * Creates and manages a Maven Resolver session with security hardening.
 *
 * Session is created once via [[SessionConfig]] and reused for all resolutions
 * within a build. The session is made read-only after configuration.
 */
final class MavenResolverEngine private (
    val system: RepositorySystem,
    val session: RepositorySystemSession
) extends AutoCloseable {

  /**
   * Execute a dependency resolution request.
   *
   * @param filter optional filter to exclude dependencies (e.g. by scope)
   */
  def resolve(
      collectRequest: CollectRequest,
      filter: DependencyFilter = null
  ): DependencyResult = {
    val dependencyRequest = DependencyRequest()
    dependencyRequest.setCollectRequest(collectRequest)
    if (filter != null) dependencyRequest.setFilter(filter)
    system.resolveDependencies(session, dependencyRequest)
  }

  override def close(): Unit = system.close()
}

object MavenResolverEngine {

  /**
   * Create a new engine from the given session configuration.
   *
   * Loads settings.xml (if present), applies mirrors/auth/proxies,
   * sets security policies, and makes the session read-only.
   */
  def apply(config: SessionConfig): MavenResolverEngine = {
    val system = RepositorySystemSupplier().get()
    val session = createSession(system, config)
    new MavenResolverEngine(system, session)
  }

  private def createSession(
      system: RepositorySystem,
      config: SessionConfig
  ): RepositorySystemSession = {
    @nowarn("msg=deprecated")
    // system.createSessionBuilder() throws classloader errors in isolated workers
    val session = DefaultRepositorySystemSession()

    // Local repository
    val localRepo = LocalRepository(File(config.localRepositoryPath))
    session.setLocalRepositoryManager(
      system.newLocalRepositoryManager(session, localRepo)
    )

    // Checksum policy
    session.setChecksumPolicy(config.checksumPolicy)

    // Preferred checksum algorithms
    session.setConfigProperty(
      "aether.checksumAlgorithms",
      config.checksumAlgorithms
    )

    // TLS protocol enforcement
    session.setConfigProperty(
      "https.protocols",
      config.tlsProtocols
    )

    // System properties — required by Maven model builder for profile activation
    // (e.g. java.version for JDK-conditional profiles)
    val sysProps = System.getProperties
    sysProps.stringPropertyNames().asScala.foreach { key =>
      session.setSystemProperty(key, sysProps.getProperty(key))
    }

    // Offline mode
    session.setOffline(config.offline)

    // Update policy — applied to remote repository policies at resolution time
    session.setUpdatePolicy(config.updatePolicy)

    // Connection/request timeout defaults
    session.setConfigProperty(
      ConfigurationProperties.CONNECT_TIMEOUT,
      java.lang.Integer.valueOf(30_000)
    )
    session.setConfigProperty(
      ConfigurationProperties.REQUEST_TIMEOUT,
      java.lang.Integer.valueOf(60_000)
    )

    // Dependency selection: exclude test/provided for transitive deps
    session.setDependencySelector(
      AndDependencySelector(
        ScopeDependencySelector("test", "provided"),
        ExclusionDependencySelector(),
        OptionalDependencySelector()
      )
    )

    // Dependency graph: conflict resolution + scope derivation (matches Maven CLI)
    session.setDependencyGraphTransformer(
      ChainedDependencyGraphTransformer(
        ConflictResolver(
          NearestVersionSelector(),
          JavaScopeSelector(),
          SimpleOptionalitySelector(),
          JavaScopeDeriver()
        ),
        JavaDependencyContextRefiner()
      )
    )

    // Dependency manager: applies <dependencyManagement> version/scope overrides
    session.setDependencyManager(ClassicDependencyManager())

    // Apply settings.xml (mirrors, authentication, proxies)
    val settings = loadSettings(config)
    applyMirrors(session, settings)
    applyAuthentication(session, settings)
    applyProxies(session, settings)

    // Make session read-only — no further mutation allowed
    session.setReadOnly()
    session
  }

  private def loadSettings(config: SessionConfig): Settings = {
    val settings =
      SettingsXmlReader.loadRawSettings(config.userSettingsPath, config.globalSettingsPath)
    SettingsXmlReader.decryptPasswords(settings)
  }

  private def applyMirrors(
      session: DefaultRepositorySystemSession,
      settings: Settings
  ): Unit = {
    val mirrorSelector = DefaultMirrorSelector()
    settings.getMirrors.asScala.foreach { mirror =>
      mirrorSelector.add(
        mirror.getId,
        mirror.getUrl,
        mirror.getLayout,
        false, // not a repository manager
        mirror.isBlocked,
        mirror.getMirrorOf,
        mirror.getMirrorOfLayouts
      )
    }
    session.setMirrorSelector(mirrorSelector)
  }

  private def applyAuthentication(
      session: DefaultRepositorySystemSession,
      settings: Settings
  ): Unit = {
    val serverMap: Map[String, Server] =
      settings.getServers.asScala.map(s => s.getId -> s).toMap

    session.setAuthenticationSelector(new org.eclipse.aether.repository.AuthenticationSelector {
      override def getAuthentication(
          repository: RemoteRepository
      ): org.eclipse.aether.repository.Authentication =
        serverMap.get(repository.getId) match {
          case Some(server) =>
            val builder = AuthenticationBuilder()
            Option(server.getUsername).foreach(builder.addUsername)
            Option(server.getPassword).foreach(builder.addPassword)
            Option(server.getPrivateKey).foreach(key =>
              builder.addPrivateKey(key, server.getPassphrase)
            )
            builder.build()
          case None => null
        }
    })
  }

  private def applyProxies(
      session: DefaultRepositorySystemSession,
      settings: Settings
  ): Unit = {
    val proxySelector = DefaultProxySelector()
    settings.getProxies.asScala
      .filter(_.isActive)
      .foreach { proxy =>
        val auth =
          if (proxy.getUsername != null)
            AuthenticationBuilder()
              .addUsername(proxy.getUsername)
              .addPassword(proxy.getPassword)
              .build()
          else null
        proxySelector.add(
          org.eclipse.aether.repository.Proxy(
            proxy.getProtocol,
            proxy.getHost,
            proxy.getPort,
            auth
          ),
          proxy.getNonProxyHosts
        )
      }
    session.setProxySelector(proxySelector)
  }
}
