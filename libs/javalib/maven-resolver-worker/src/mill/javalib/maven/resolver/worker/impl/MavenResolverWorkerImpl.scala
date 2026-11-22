package mill.javalib.maven.resolver.worker.impl

import mill.javalib.internal.MavenResolverSupport
import mill.javalib.internal.MavenResolverSupport.*
import mill.javalib.maven.resolver.core.{
  MavenResolverEngine,
  SessionConfig as CoreSessionConfig
}
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.{DependencyNode, Dependency, Exclusion}
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.{ArtifactDescriptorRequest, ArtifactRequest, ArtifactResult}
import org.eclipse.aether.util.artifact.JavaScopes
import org.eclipse.aether.util.filter.DependencyFilterUtils

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.util.Try

//noinspection ScalaUnusedSymbol - invoked dynamically as a worker.
class MavenResolverWorkerImpl extends MavenResolverSupport.Api {

  private var engine: MavenResolverEngine = uninitialized
  private var lastArtifacts: Seq[ResolvedArtifact] = Seq.empty

  override def initialize(config: SessionConfig): Unit = {
    if (engine != null) engine.close()
    lastArtifacts = Seq.empty
    engine = MavenResolverEngine(CoreSessionConfig(
      localRepositoryPath = config.localRepositoryPath,
      userSettingsPath = config.userSettingsPath,
      globalSettingsPath = config.globalSettingsPath,
      checksumPolicy = config.checksumPolicy,
      checksumAlgorithms = config.checksumAlgorithms,
      tlsProtocols = config.tlsProtocols,
      offline = config.offline,
      updatePolicy = config.updatePolicy,
      snapshotsEnabled = config.snapshotsEnabled
    ))
  }

  override def resolveClasspath(
      deps: Seq[DependencyCoord],
      repositories: Seq[RepoCoord],
      sources: Boolean,
      forceVersions: Map[String, String],
      bomDeps: Seq[BomCoord]
  ): Seq[String] = {
    requireInitialized()
    try {
      val (resolved, repos) = resolveCommon(deps, repositories, forceVersions, bomDeps)
      if (sources) fetchSourcePaths(resolved, repos)
      else resolved.map(_.getArtifact.getPath.toAbsolutePath.toString)
    } catch {
      case e: Exception =>
        throw RuntimeException(s"[maven-resolver] ${e.getMessage}", e)
    }
  }

  override def resolveArtifacts(
      deps: Seq[DependencyCoord],
      repositories: Seq[RepoCoord],
      sources: Boolean,
      forceVersions: Map[String, String],
      bomDeps: Seq[BomCoord]
  ): Seq[ResolvedArtifact] = {
    requireInitialized()
    try {
      val (resolved, repos) = resolveCommon(deps, repositories, forceVersions, bomDeps)
      val artifacts = if (sources) fetchSourceArtifacts(resolved, repos)
      else resolved.map(toResolvedArtifact)
      lastArtifacts = artifacts
      artifacts
    } catch {
      case e: Exception =>
        throw RuntimeException(s"[maven-resolver] ${e.getMessage}", e)
    }
  }

  override def resolveMetadata(
      deps: Seq[DependencyCoord],
      repositories: Seq[RepoCoord],
      forceVersions: Map[String, String],
      bomDeps: Seq[BomCoord]
  ): ResolutionMetadata = {
    requireInitialized()
    try {
      val (collectRequest, repos) = buildCollectRequest(deps, repositories, forceVersions, bomDeps)
      val classpathFilter =
        DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME)
      val result = engine.resolve(collectRequest, classpathFilter)
      val rootNode = result.getRoot

      val transitiveDeps = buildTransitiveDeps(rootNode)
      val orderedDeps = buildOrderedDeps(rootNode)
      val projectCache = buildProjectCache(rootNode, repos)

      ResolutionMetadata(transitiveDeps, projectCache, orderedDeps)
    } catch {
      case e: Exception =>
        throw RuntimeException(s"[maven-resolver] ${e.getMessage}", e)
    }
  }

  override def lastResolvedArtifacts(): Seq[ResolvedArtifact] = lastArtifacts

  override def close(): Unit = {
    if (engine != null) {
      engine.close()
      engine = null
    }
  }

  // --- Common resolution infrastructure ---

  /** Build a CollectRequest from dependency/repo/bom/force-version parameters. */
  private def buildCollectRequest(
      deps: Seq[DependencyCoord],
      repositories: Seq[RepoCoord],
      forceVersions: Map[String, String],
      bomDeps: Seq[BomCoord]
  ): (CollectRequest, Seq[RemoteRepository]) = {
    val repos = repositories.map(toRemoteRepository)
    val collectRequest = CollectRequest()
    repos.foreach(collectRequest.addRepository)
    deps.foreach(dep => collectRequest.addDependency(toDependency(dep)))

    // BOMs as managed dependencies with import scope
    bomDeps.foreach { bom =>
      val bomArtifact = DefaultArtifact(bom.groupId, bom.artifactId, "", "pom", bom.version)
      collectRequest.addManagedDependency(Dependency(bomArtifact, "import"))
    }

    // Force versions as managed dependencies (version overrides)
    forceVersions.foreach { case (key, version) =>
      val parts = key.split(":", 2)
      if (parts.length == 2) {
        val artifact = DefaultArtifact(parts(0), parts(1), "jar", version)
        collectRequest.addManagedDependency(Dependency(artifact, null))
      }
    }

    (collectRequest, repos)
  }

  /** Build and execute a dependency resolution, returning resolved artifact results and repos. */
  private def resolveCommon(
      deps: Seq[DependencyCoord],
      repositories: Seq[RepoCoord],
      forceVersions: Map[String, String],
      bomDeps: Seq[BomCoord]
  ): (Seq[ArtifactResult], Seq[RemoteRepository]) = {
    val (collectRequest, repos) = buildCollectRequest(deps, repositories, forceVersions, bomDeps)
    val classpathFilter =
      DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME)
    val result = engine.resolve(collectRequest, classpathFilter)
    val resolved = result.getArtifactResults.asScala.filter(_.isResolved).toSeq
    (resolved, repos)
  }

  /** Fetch source JARs for resolved artifacts, returning their paths. */
  private def fetchSourcePaths(
      resolved: Seq[ArtifactResult],
      repos: Seq[RemoteRepository]
  ): Seq[String] =
    resolved.flatMap { ar =>
      val a = ar.getArtifact
      val sourceArtifact = DefaultArtifact(
        a.getGroupId, a.getArtifactId, "sources", "jar", a.getVersion
      )
      val request = ArtifactRequest(sourceArtifact, repos.asJava, null)
      Try(engine.system.resolveArtifact(engine.session, request))
        .toOption
        .filter(_.isResolved)
        .map(_.getArtifact.getPath.toAbsolutePath.toString)
    }

  /** Fetch source JARs for resolved artifacts, returning ResolvedArtifact with metadata. */
  private def fetchSourceArtifacts(
      resolved: Seq[ArtifactResult],
      repos: Seq[RemoteRepository]
  ): Seq[ResolvedArtifact] =
    resolved.flatMap { ar =>
      val a = ar.getArtifact
      val sourceArtifact = DefaultArtifact(
        a.getGroupId, a.getArtifactId, "sources", "jar", a.getVersion
      )
      val request = ArtifactRequest(sourceArtifact, repos.asJava, null)
      Try(engine.system.resolveArtifact(engine.session, request))
        .toOption
        .filter(_.isResolved)
        .map(toResolvedArtifact)
    }

  /** Convert an ArtifactResult to a ResolvedArtifact with repository URL provenance. */
  private def toResolvedArtifact(ar: ArtifactResult): ResolvedArtifact = {
    val a = ar.getArtifact
    val repoUrl = ar.getRepository match {
      case r: RemoteRepository => r.getUrl
      case _ => ""
    }
    ResolvedArtifact(
      groupId = a.getGroupId,
      artifactId = a.getArtifactId,
      version = a.getVersion,
      classifier = Option(a.getClassifier).getOrElse(""),
      path = a.getPath.toAbsolutePath.toString,
      repositoryUrl = repoUrl
    )
  }

  private def requireInitialized(): Unit =
    if (engine == null)
      throw IllegalStateException(
        "[maven-resolver] Worker not initialized. Call initialize() first."
      )

  private def toDependency(dep: DependencyCoord): Dependency = {
    val extension = if (dep.`type`.isEmpty) "jar" else dep.`type`
    val artifact = DefaultArtifact(
      dep.groupId, dep.artifactId, dep.classifier, extension, dep.version
    )
    val exclusions = dep.exclusions.map { case (g, a) =>
      Exclusion(g, a, "*", "*")
    }.asJava
    val scope = if (dep.configuration.isEmpty) "compile" else dep.configuration
    Dependency(artifact, scope).setExclusions(exclusions)
  }

  private def toRemoteRepository(repo: RepoCoord): RemoteRepository =
    RemoteRepository.Builder(
      repo.id,
      if (repo.`type`.isEmpty) "default" else repo.`type`,
      repo.url
    ).build()

  // --- Metadata tree-walking infrastructure ---

  private def depKey(dep: Dependency): String = {
    val a = dep.getArtifact
    s"${a.getGroupId}:${a.getArtifactId}:${a.getVersion}"
  }

  private def nodeToResolvedDep(dep: Dependency): ResolvedDep = {
    val a = dep.getArtifact
    ResolvedDep(
      groupId = a.getGroupId,
      artifactId = a.getArtifactId,
      version = a.getVersion,
      configuration = Option(dep.getScope).getOrElse("compile")
    )
  }

  /** Map each dep to its direct children in the resolved tree. */
  private def buildTransitiveDeps(root: DependencyNode): Map[String, Seq[ResolvedDep]] = {
    val result = mutable.Map[String, Seq[ResolvedDep]]()
    def walk(node: DependencyNode): Unit = {
      val dep = node.getDependency
      if (dep != null) {
        val key = depKey(dep)
        if (!result.contains(key)) {
          val children = node.getChildren.asScala.toSeq
            .filter(_.getDependency != null)
            .map(child => nodeToResolvedDep(child.getDependency))
          result(key) = children
          node.getChildren.asScala.foreach(walk)
        }
      }
    }
    root.getChildren.asScala.foreach(walk)
    result.toMap
  }

  /** DFS traversal of all resolved deps in tree order. */
  private def buildOrderedDeps(root: DependencyNode): Seq[ResolvedDep] = {
    val visited = mutable.Set[String]()
    val result = mutable.ListBuffer[ResolvedDep]()
    def walk(node: DependencyNode): Unit = {
      val dep = node.getDependency
      if (dep != null) {
        val key = s"${dep.getArtifact.getGroupId}:${dep.getArtifact.getArtifactId}"
        if (visited.add(key)) {
          result += nodeToResolvedDep(dep)
        }
      }
      node.getChildren.asScala.foreach(walk)
    }
    root.getChildren.asScala.foreach(walk)
    result.toSeq
  }

  /** Read POM metadata for all resolved deps. */
  private def buildProjectCache(
      root: DependencyNode,
      repos: Seq[RemoteRepository]
  ): Map[String, ProjectInfo] = {
    val result = mutable.Map[String, ProjectInfo]()
    def walk(node: DependencyNode): Unit = {
      val dep = node.getDependency
      if (dep != null) {
        val key = depKey(dep)
        if (!result.contains(key)) {
          result(key) = readProjectInfo(dep.getArtifact, repos)
          node.getChildren.asScala.foreach(walk)
        }
      }
    }
    root.getChildren.asScala.foreach(walk)
    result.toMap
  }

  /** Read overrides, licenses, parent from a POM. */
  private def readProjectInfo(
      artifact: org.eclipse.aether.artifact.Artifact,
      repos: Seq[RemoteRepository]
  ): ProjectInfo = {
    val overrides = Try {
      val req = ArtifactDescriptorRequest(artifact, repos.asJava, null)
      val desc = engine.system.readArtifactDescriptor(engine.session, req)
      desc.getManagedDependencies.asScala.toSeq.map { md =>
        val ma = md.getArtifact
        val key = (
          ma.getGroupId,
          ma.getArtifactId,
          Option(ma.getExtension).filter(_.nonEmpty).getOrElse("jar"),
          Option(ma.getClassifier).getOrElse("")
        )
        key -> ma.getVersion
      }
    }.getOrElse(Seq.empty)

    val (licenses, parent) = Try {
      val pomArtifact = DefaultArtifact(
        artifact.getGroupId, artifact.getArtifactId, "", "pom", artifact.getVersion
      )
      val pomResult = engine.system.resolveArtifact(
        engine.session, ArtifactRequest(pomArtifact, repos.asJava, null)
      )
      parsePomMetadata(pomResult.getArtifact.getPath)
    }.getOrElse((Seq.empty, None))

    ProjectInfo(overrides, licenses, parent)
  }

  /** Parse license and parent info from a POM file. */
  private def parsePomMetadata(
      pomPath: java.nio.file.Path
  ): (Seq[(String, String)], Option[(String, String, String)]) = {
    import javax.xml.parsers.DocumentBuilderFactory
    import org.w3c.dom.{Element, Node}

    val dbf = DocumentBuilderFactory.newInstance()
    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false)
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    val doc = dbf.newDocumentBuilder().parse(pomPath.toFile)
    val root = doc.getDocumentElement
    root.normalize()

    def directChild(el: Element, tag: String): Option[Element] = {
      val nodes = el.getChildNodes
      (0 until nodes.getLength).collectFirst {
        case i if nodes.item(i).getNodeType == Node.ELEMENT_NODE &&
            nodes.item(i).asInstanceOf[Element].getTagName == tag =>
          nodes.item(i).asInstanceOf[Element]
      }
    }

    def directChildren(el: Element, tag: String): Seq[Element] = {
      val nodes = el.getChildNodes
      (0 until nodes.getLength).collect {
        case i if nodes.item(i).getNodeType == Node.ELEMENT_NODE &&
            nodes.item(i).asInstanceOf[Element].getTagName == tag =>
          nodes.item(i).asInstanceOf[Element]
      }.toSeq
    }

    def childText(el: Element, tag: String): Option[String] =
      directChild(el, tag).map(_.getTextContent).filter(_.nonEmpty)

    val licenses = directChild(root, "licenses").toSeq.flatMap { licensesEl =>
      directChildren(licensesEl, "license").flatMap { licenseEl =>
        childText(licenseEl, "name").map(_ -> childText(licenseEl, "url").getOrElse(""))
      }
    }

    val parent = directChild(root, "parent").flatMap { parentEl =>
      for {
        g <- childText(parentEl, "groupId")
        a <- childText(parentEl, "artifactId")
        v <- childText(parentEl, "version")
      } yield (g, a, v)
    }

    (licenses, parent)
  }
}
