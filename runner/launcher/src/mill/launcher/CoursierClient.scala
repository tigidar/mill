package mill.launcher

import coursier.{Artifacts, Dependency, ModuleName, Organization, Resolve, VersionConstraint}
import coursier.cache.{ArchiveCache, FileCache}
import coursier.jvm.{JavaHome, JvmCache, JvmIndex}
import coursier.util.Task
import coursier.core.Module
import mill.api.ResolverType
import mill.constants.BuildInfo
import mill.javalib.maven.resolver.core.{MavenResolverEngine, SessionConfig}
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency as MavenDependency
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.artifact.JavaScopes
import org.eclipse.aether.util.filter.DependencyFilterUtils
import upickle.default.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters.*
import mill.api.JsonFormatters.*

object CoursierClient {

  private def cacheDir(outDir: String): os.Path =
    os.Path(outDir, os.pwd) / "mill-daemon" / "cache"

  /**
   * Single-entry disk cache for expensive Coursier resolutions, as even when everything
   * is already downloaded Coursier's cache checking can take >100ms
   *
   * Stores a JSON tuple of (cacheKey, data) in the cache file.
   */
  private def cached[T: ReadWriter](
      cacheFile: os.Path,
      cacheKey: String,
      // Make sure we validate the correctness of cache entries because they may have
      // been deleted or moved in between the cache being populated and us using it
      validate: T => Boolean
  )(compute: => T): T = {
    // Try to read from cache
    val cachedValue: Option[T] =
      if (os.exists(cacheFile)) {
        try {
          val content = os.read(cacheFile)
          val (storedKey, value) = read[(String, T)](content)
          if (storedKey == cacheKey && validate(value)) Some(value) else None
        } catch {
          case _: Exception => None
        }
      } else None

    cachedValue match {
      case Some(value) => value
      case None =>
        val value = compute
        os.write.over(cacheFile, write((cacheKey, value)), createFolders = true)
        value
    }
  }

  def resolveMillDaemon(
      outDir: String,
      millRepositories: Seq[String],
      resolverType: ResolverType = ResolverType.Coursier
  ): Seq[os.Path] = resolverType match {
    case ResolverType.Maven => resolveMillDaemonMaven(outDir, millRepositories)
    case ResolverType.Coursier => resolveMillDaemonCoursier(outDir, millRepositories)
  }

  private def resolveMillDaemonCoursier(
      outDir: String,
      millRepositories: Seq[String]
  ): Seq[os.Path] = {
    // FIXME All the messing with COURSIER_REPOSITORIES assumes user override repos only via this env var,
    // rather than via Java properties or config files, whose use is less likely. Things might go wrong
    // if ever users rely on those.
    val overridesRepos = Option(System.getenv("COURSIER_REPOSITORIES"))
      .toSeq
      .flatMap(_.split('|').toSeq)

    val millRepositories0 = millRepositories.sorted

    val cacheKey =
      s"${BuildInfo.millVersion} ${overridesRepos.reverse.mkString("|")}|${millRepositories0.mkString("|")}"

    cached[Seq[os.Path]](
      cacheFile = cacheDir(outDir) / "mill-daemon-classpath",
      cacheKey = cacheKey,
      validate = paths => paths.forall(os.exists(_))
    ) {
      val coursierCache0 = FileCache.create[Task]().copy(
        logger = coursier.cache.loggers.RefreshLogger.create()
      )

      val configuredRepos = mill.util.Jvm.reposFromStrings(millRepositories0).get

      val artifactsResultOrError = {
        // configuredRepos (from mill-repositories) comes first so user config takes precedence
        val allRepos = configuredRepos ++ Resolve.defaultRepositories
        val resolve = Resolve().copy(
          cache = coursierCache0,
          dependencies = Seq(Dependency(
            Module(Organization("com.lihaoyi"), ModuleName("mill-runner-daemon_3"), Map()),
            VersionConstraint(BuildInfo.millVersion)
          )),
          repositories = allRepos
        )

        val result = resolve.either().flatMap { v =>
          Artifacts(coursierCache0)
            .withResolution(v)
            .eitherResult()
        }
        result match {
          case Left(e) => sys.error(e.toString())
          case Right(v) => v
        }
      }

      artifactsResultOrError.artifacts.map(_._2.toString).toSeq.map(os.Path(_))
    }
  }

  /**
   * Resolve Mill daemon classpath using Maven Resolver (no OkHttp/Coursier).
   *
   * Uses `MavenResolverEngine` directly since the launcher runs before the daemon
   * exists, so the worker pattern is not available.
   */
  private def resolveMillDaemonMaven(
      outDir: String,
      millRepositories: Seq[String]
  ): Seq[os.Path] = {
    val millRepositories0 = millRepositories.sorted
    val cacheKey = s"maven:${BuildInfo.millVersion}|${millRepositories0.mkString("|")}"

    cached[Seq[os.Path]](
      cacheFile = cacheDir(outDir) / "mill-daemon-classpath",
      cacheKey = cacheKey,
      validate = paths => paths.forall(os.exists(_))
    ) {
      val localRepo = sys.props.getOrElse(
        "maven.repo.local",
        os.Path(sys.props("user.home")) / ".m2" / "repository"
      ).toString

      val userSettings = {
        val p = os.Path(sys.props("user.home")) / ".m2" / "settings.xml"
        Option.when(os.exists(p))(p.toString)
      }

      val engine = MavenResolverEngine(SessionConfig(
        localRepositoryPath = localRepo,
        userSettingsPath = userSettings,
        checksumPolicy = "fail",
        checksumAlgorithms = "SHA-256,SHA-1",
        tlsProtocols = "TLSv1.2,TLSv1.3"
      ))

      try {
        val collectRequest = CollectRequest()

        // Add configured repositories first (user config takes precedence)
        millRepositories0.foreach { url =>
          val repo = RemoteRepository.Builder(url, "default", url).build()
          collectRequest.addRepository(repo)
        }

        // Always include Maven Central as fallback
        val central = RemoteRepository.Builder(
          "central",
          "default",
          "https://repo1.maven.org/maven2/"
        ).build()
        collectRequest.addRepository(central)

        // The single dependency: mill-runner-daemon_3
        val artifact = DefaultArtifact(
          "com.lihaoyi",
          "mill-runner-daemon_3",
          "jar",
          BuildInfo.millVersion
        )
        collectRequest.addDependency(MavenDependency(artifact, JavaScopes.COMPILE))

        val classpathFilter =
          DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME)
        val result = engine.resolve(collectRequest, classpathFilter)
        result.getArtifactResults.asScala
          .filter(_.isResolved)
          .map(ar => os.Path(ar.getArtifact.getPath.toAbsolutePath))
          .toSeq
      } finally {
        engine.close()
      }
    }
  }

  def resolveJavaHome(
      id: String,
      jvmIndexVersionOpt: Option[String],
      outDir: String,
      millRepositories: Seq[String],
      resolverType: ResolverType = ResolverType.Coursier
  ): os.Path = {
    resolverType match {
      case ResolverType.Maven =>
        // Maven resolver backend cannot download JVMs — require system JVM
        val javaHomePath = Option(System.getenv("JAVA_HOME"))
          .orElse(sys.props.get("java.home"))
        javaHomePath match {
          case Some(home) => os.Path(home)
          case None =>
            throw new IllegalStateException(
              "Maven resolver backend requires a system-installed JVM. " +
                "Set JAVA_HOME or use `//| mill-jvm-version: system` in your build."
            )
        }

      case ResolverType.Coursier =>
        val indexVersion =
          jvmIndexVersionOpt.getOrElse(mill.client.Versions.coursierJvmIndexVersion)
        val cacheKey = s"$id:$indexVersion:${millRepositories.sorted.mkString(":")}"

        cached[os.Path](
          cacheFile = cacheDir(outDir) / "java-home",
          cacheKey = cacheKey,
          validate = os.isDir(_)
        ) {
          val coursierCache0 = FileCache.create[Task]().copy(
            logger = coursier.cache.loggers.RefreshLogger.create()
          )

          val configuredRepos = mill.util.Jvm.reposFromStrings(millRepositories).get
          val jvmCache = JvmCache().copy(
            archiveCache = ArchiveCache().copy(cache = coursierCache0),
            index = Some(JvmIndex.load(
              cache = coursierCache0,
              repositories = configuredRepos ++ Resolve.defaultRepositories,
              indexChannel = mill.util.Jvm.jvmIndexChannel(indexVersion)
            ))
          )

          val javaHome = JavaHome().copy(
            cache = Some(jvmCache),
            // when given a version like "17", always pick highest version in the index
            // rather than the highest already on disk
            update = true
          )

          os.Path(
            coursierCache0.logger.using(javaHome.get(id)).unsafeRun()(using coursierCache0.ec)
          )
        }
    }
  }
}
