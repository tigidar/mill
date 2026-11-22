package mill.javalib

import coursier.core.{
  ArtifactSource,
  Classifier,
  Configuration,
  DependencyManagement,
  Extension,
  Info,
  MinimizedExclusions,
  Module,
  ModuleName,
  Organization,
  Overrides,
  Project,
  Publication,
  Type,
  Variant,
  VariantPublication
}
import coursier.util.Artifact
import coursier.version.{Version as Version0, VersionConstraint as VersionConstraint0}
import coursier.{Dependency, Fetch, Resolution}
import mill.javalib.internal.MavenResolverSupport.{
  ResolvedArtifact,
  ResolvedDep,
  ResolutionMetadata
}

import java.io.File

/**
 * Converts Maven Resolver result types to Coursier types, enabling the Maven
 * backend to produce the same result types as the Coursier backend.
 */
private[javalib] object CoursierTypeAdapter {

  private type DetailedArtifact =
    (Dependency, Either[VariantPublication, Publication], Artifact, Option[File])

  /** Standard Maven scope configuration mapping. */
  private val mavenConfigurations: Map[Configuration, Seq[Configuration]] = Map(
    Configuration.compile -> Nil,
    Configuration("runtime") -> Seq(Configuration.compile),
    Configuration("provided") -> Nil,
    Configuration.defaultCompile -> Seq(Configuration.compile)
  )

  /** No-op artifact source — Maven resolution already resolved artifacts. */
  private object NoopArtifactSource extends ArtifactSource {
    override def artifacts(
        dependency: Dependency,
        project: Project,
        overrideClassifiers: Option[Seq[Classifier]]
    ): Seq[(Publication, Artifact)] = Nil
  }

  /**
   * Construct a Coursier [[Resolution]] from Maven Resolver metadata.
   *
   * Populates `finalDependenciesCache`, `projectCache0`, and `dependencySet`
   * so that callers using `orderedDependencies`, `dependenciesOf0`,
   * `projectCache`, and `finalDependenciesCache` get correct results.
   *
   * @param metadata resolved metadata from the Maven Resolver worker
   * @param rootDeps the original Coursier root dependencies (used as cache keys)
   * @param mapDependencies optional dependency mapping function
   */
  def toResolution(
      metadata: ResolutionMetadata,
      rootDeps: Seq[Dependency],
      mapDependencies: Option[Dependency => Dependency]
  ): Resolution = {
    // Canonical conversion: ResolvedDep → Coursier Dependency
    val depCache = scala.collection.mutable.Map[String, Dependency]()

    def resolvedDepToDep(rd: ResolvedDep): Dependency = {
      val key = s"${rd.groupId}:${rd.artifactId}:${rd.version}"
      depCache.getOrElseUpdate(key, {
        val module = Module(Organization(rd.groupId), ModuleName(rd.artifactId), Map.empty)
        Dependency(module, rd.version)
          .withConfiguration(Configuration(rd.configuration))
      })
    }

    // Register root deps in the cache so they map correctly
    rootDeps.foreach { dep =>
      val d = mapDependencies.fold(dep)(_.apply(dep))
      val key = s"${d.module.organization.value}:${d.module.name.value}:${d.version}"
      depCache(key) = d
    }

    // Build finalDependenciesCache: dep → its direct children
    val finalDepsCache: Map[Dependency, Seq[Dependency]] =
      metadata.transitiveDeps.flatMap { case (key, children) =>
        depCache.get(key).orElse {
          // Create a dep for keys not yet in cache (transitive deps as parents)
          val parts = key.split(":", 3)
          if (parts.length == 3) {
            val module = Module(Organization(parts(0)), ModuleName(parts(1)), Map.empty)
            val dep = Dependency(module, parts(2))
            depCache(key) = dep
            Some(dep)
          } else None
        }.map { dep =>
          dep -> children.map(resolvedDepToDep)
        }
      }

    // Build projectCache0: (Module, VersionConstraint) → (ArtifactSource, Project)
    val projCache: Map[(Module, VersionConstraint0), (ArtifactSource, Project)] =
      metadata.projectCache.map { case (key, pInfo) =>
        val parts = key.split(":", 3)
        val (g, a, v) = (parts(0), parts(1), parts(2))
        val module = Module(Organization(g), ModuleName(a), Map.empty)
        val vc = VersionConstraint0(v)

        // Overrides: (groupId, artifactId, type, classifier) → version
        val overridesMap: Map[DependencyManagement.Key, DependencyManagement.Values] =
          pInfo.overrides.map { case ((og, oa, ot, oc), ver) =>
            DependencyManagement.Key(
              Organization(og),
              ModuleName(oa),
              Type(ot),
              Classifier(oc)
            ) -> DependencyManagement.Values(
              Configuration.empty,
              VersionConstraint0(ver),
              MinimizedExclusions.zero,
              optional = false
            )
          }.toMap

        // Licenses
        val licenseInfo: Seq[Info.License] = pInfo.licenses.map { case (name, url) =>
          Info.License(name, Option(url).filter(_.nonEmpty), None, None)
        }

        // Parent
        val parent0: Option[(Module, VersionConstraint0)] = pInfo.parent.map { case (pg, pa, pv) =>
          (Module(Organization(pg), ModuleName(pa), Map.empty), VersionConstraint0(pv))
        }

        // Dependencies from transitiveDeps (direct children of this dep)
        val childDeps: Seq[(Variant, Dependency)] =
          metadata.transitiveDeps.getOrElse(key, Nil).map { rd =>
            (
              Variant.Configuration(Configuration(rd.configuration)): Variant,
              resolvedDepToDep(rd)
            )
          }

        val project = Project(
          module = module,
          version0 = Version0(v),
          dependencies0 = childDeps,
          configurations = mavenConfigurations,
          parent0 = parent0,
          dependencyManagement0 = Nil,
          properties = Nil,
          profiles = Nil,
          versions = None,
          snapshotVersioning = None,
          packagingOpt = Some(Type.jar),
          relocated = false,
          actualVersionOpt0 = Some(Version0(v)),
          publications0 = Nil,
          info = Info("", "", Nil, None, None, licenseInfo),
          overrides = Overrides(overridesMap),
          variants = Map.empty,
          variantPublications = Map.empty
        )

        (module, vc) -> (NoopArtifactSource: ArtifactSource, project)
      }

    // All resolved deps
    val allDeps: Set[Dependency] = metadata.orderedDeps.map(resolvedDepToDep).toSet

    Resolution()
      .withRootDependencies(rootDeps.map(d => mapDependencies.fold(d)(_.apply(d))))
      .withDependencies(allDeps)
      .withFinalDependenciesCache(finalDepsCache)
      .withProjectCache0(projCache)
  }

  /** Convert resolved artifacts to a Coursier [[Fetch.Result]]. */
  def toFetchResult(artifacts: Seq[ResolvedArtifact]): Fetch.Result = {
    val tuples: Seq[DetailedArtifact] = artifacts.map(toDetailedArtifact)
    val deps = tuples.map(_._1)
    Fetch.Result(Resolution(deps), tuples, Nil)
  }

  /** Convert resolved artifacts to a Coursier [[coursier.Artifacts.Result]]. */
  def toArtifactsResult(artifacts: Seq[ResolvedArtifact]): coursier.Artifacts.Result =
    coursier.Artifacts.Result(artifacts.map(toDetailedArtifact), Nil)

  /** Convert a single [[ResolvedArtifact]] to the Coursier 4-tuple used in detailed results. */
  private[javalib] def toDetailedArtifact(ra: ResolvedArtifact): DetailedArtifact = {
    val module = Module(
      Organization(ra.groupId),
      ModuleName(ra.artifactId),
      Map.empty
    )
    val dep = Dependency(module, ra.version)

    val pub: Either[VariantPublication, Publication] = Right(Publication(
      ra.artifactId,
      Type("jar"),
      Extension("jar"),
      Classifier(ra.classifier)
    ))

    val art = Artifact(s"file://${ra.path}")

    (dep, pub, art, Some(File(ra.path)))
  }
}
