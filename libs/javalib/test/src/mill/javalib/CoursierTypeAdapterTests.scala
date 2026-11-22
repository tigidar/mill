package mill.javalib

import coursier.core.{Configuration, Module, ModuleName, Organization}
import coursier.Dependency
import mill.javalib.internal.MavenResolverSupport.{
  ProjectInfo,
  ResolvedArtifact,
  ResolvedDep,
  ResolutionMetadata
}
import utest.*

object CoursierTypeAdapterTests extends TestSuite {

  private val sampleArtifact = ResolvedArtifact(
    groupId = "org.apache.commons",
    artifactId = "commons-lang3",
    version = "3.14.0",
    classifier = "",
    path = "/tmp/test-repo/commons-lang3-3.14.0.jar",
    repositoryUrl = "https://repo.maven.apache.org/maven2"
  )

  private val sourcesArtifact = ResolvedArtifact(
    groupId = "org.apache.commons",
    artifactId = "commons-lang3",
    version = "3.14.0",
    classifier = "sources",
    path = "/tmp/test-repo/commons-lang3-3.14.0-sources.jar",
    repositoryUrl = "https://repo.maven.apache.org/maven2"
  )

  // --- Test fixtures for toResolution ---

  private def mkDep(g: String, a: String, v: String): Dependency =
    Dependency(Module(Organization(g), ModuleName(a), Map.empty), v)

  private val rootDep = mkDep("org.apache.commons", "commons-text", "1.11.0")
  private val transitiveDep = ResolvedDep("org.apache.commons", "commons-lang3", "3.14.0", "compile")

  private val sampleMetadata = ResolutionMetadata(
    transitiveDeps = Map(
      "org.apache.commons:commons-text:1.11.0" -> Seq(transitiveDep),
      "org.apache.commons:commons-lang3:3.14.0" -> Seq.empty
    ),
    projectCache = Map(
      "org.apache.commons:commons-text:1.11.0" -> ProjectInfo(
        overrides = Seq((("org.junit", "junit", "jar", ""), "4.13.2")),
        licenses = Seq(("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt")),
        parent = Some(("org.apache.commons", "commons-parent", "64"))
      ),
      "org.apache.commons:commons-lang3:3.14.0" -> ProjectInfo(
        overrides = Seq.empty,
        licenses = Seq.empty,
        parent = Some(("org.apache.commons", "commons-parent", "64"))
      )
    ),
    orderedDeps = Seq(
      ResolvedDep("org.apache.commons", "commons-text", "1.11.0", "compile"),
      transitiveDep
    )
  )

  val tests: Tests = Tests {

    test("toDetailedArtifactMapsOrganization") {
      val (dep, _, _, _) = CoursierTypeAdapter.toDetailedArtifact(sampleArtifact)
      assert(dep.module.organization.value == "org.apache.commons")
    }

    test("toDetailedArtifactMapsName") {
      val (dep, _, _, _) = CoursierTypeAdapter.toDetailedArtifact(sampleArtifact)
      assert(dep.module.name.value == "commons-lang3")
    }

    test("toDetailedArtifactMapsVersion") {
      val (dep, _, _, _) = CoursierTypeAdapter.toDetailedArtifact(sampleArtifact)
      assert(dep.versionConstraint.asString == "3.14.0")
    }

    test("toDetailedArtifactMapsClassifier") {
      val (_, pubEither, _, _) = CoursierTypeAdapter.toDetailedArtifact(sourcesArtifact)
      val pub = pubEither.toOption.get
      assert(pub.classifier.value == "sources")
    }

    test("toDetailedArtifactMapsEmptyClassifier") {
      val (_, pubEither, _, _) = CoursierTypeAdapter.toDetailedArtifact(sampleArtifact)
      val pub = pubEither.toOption.get
      assert(pub.classifier.value == "")
    }

    test("toDetailedArtifactMapsFilePath") {
      val (_, _, _, fileOpt) = CoursierTypeAdapter.toDetailedArtifact(sampleArtifact)
      val file = fileOpt.get
      assert(file.getAbsolutePath == "/tmp/test-repo/commons-lang3-3.14.0.jar")
    }

    test("toFetchResultContainsAllArtifacts") {
      val result = CoursierTypeAdapter.toFetchResult(Seq(sampleArtifact, sourcesArtifact))
      assert(result.detailedArtifacts0.size == 2)
    }

    test("toFetchResultResolutionHasRootDeps") {
      val result = CoursierTypeAdapter.toFetchResult(Seq(sampleArtifact))
      assert(result.resolution.rootDependencies.nonEmpty)
    }

    test("toArtifactsResultContainsAllArtifacts") {
      val result = CoursierTypeAdapter.toArtifactsResult(Seq(sampleArtifact, sourcesArtifact))
      assert(result.detailedArtifacts0.size == 2)
    }

    test("toArtifactsResultFilePathsCorrect") {
      val result = CoursierTypeAdapter.toArtifactsResult(Seq(sampleArtifact))
      val files = result.files
      assert(files.nonEmpty)
      assert(files.head.getAbsolutePath == "/tmp/test-repo/commons-lang3-3.14.0.jar")
    }

    // --- toResolution tests (ticket 012) ---

    test("toResolutionFinalDependenciesCache") {
      val resolution = CoursierTypeAdapter.toResolution(sampleMetadata, Seq(rootDep), None)
      val cache = resolution.finalDependenciesCache
      // root dep should map to its direct children
      val rootEntry = cache.find { case (dep, _) =>
        dep.module.organization.value == "org.apache.commons" &&
        dep.module.name.value == "commons-text"
      }
      assert(rootEntry.isDefined)
      val children = rootEntry.get._2
      assert(children.exists(d =>
        d.module.name.value == "commons-lang3" &&
        d.versionConstraint.asString == "3.14.0"
      ))
    }

    test("toResolutionOrderedDependencies") {
      val resolution = CoursierTypeAdapter.toResolution(sampleMetadata, Seq(rootDep), None)
      val deps = resolution.dependencies
      // Should contain both the root dep and its transitive dep
      assert(deps.exists(_.module.name.value == "commons-text"))
      assert(deps.exists(_.module.name.value == "commons-lang3"))
    }

    test("toResolutionProjectCacheOverrides") {
      val resolution = CoursierTypeAdapter.toResolution(sampleMetadata, Seq(rootDep), None)
      // Access project cache for the root dep
      val projOpt = resolution.projectCache0.find { case ((mod, _), _) =>
        mod.name.value == "commons-text"
      }
      assert(projOpt.isDefined)
      val (_, (_, proj)) = projOpt.get
      val overridesMap = proj.overrides.flatten.toMap
      // Should have the junit override
      assert(overridesMap.nonEmpty)
    }

    test("toResolutionProjectCacheLicenses") {
      val resolution = CoursierTypeAdapter.toResolution(sampleMetadata, Seq(rootDep), None)
      val projOpt = resolution.projectCache0.find { case ((mod, _), _) =>
        mod.name.value == "commons-text"
      }
      assert(projOpt.isDefined)
      val (_, (_, proj)) = projOpt.get
      assert(proj.info.licenseInfo.nonEmpty)
      assert(proj.info.licenseInfo.head.name == "Apache-2.0")
    }

    test("toResolutionProjectCacheParent") {
      val resolution = CoursierTypeAdapter.toResolution(sampleMetadata, Seq(rootDep), None)
      val projOpt = resolution.projectCache0.find { case ((mod, _), _) =>
        mod.name.value == "commons-text"
      }
      assert(projOpt.isDefined)
      val (_, (_, proj)) = projOpt.get
      assert(proj.parent0.isDefined)
      val (parentMod, parentVer) = proj.parent0.get
      assert(parentMod.name.value == "commons-parent")
      assert(parentVer.asString == "64")
    }

    test("toResolutionRootDependencies") {
      val resolution = CoursierTypeAdapter.toResolution(sampleMetadata, Seq(rootDep), None)
      assert(resolution.rootDependencies.size == 1)
      assert(resolution.rootDependencies.head.module.name.value == "commons-text")
    }
  }
}
