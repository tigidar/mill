package mill.javalib.maven.resolver.worker.impl

import mill.javalib.internal.MavenResolverSupport.*
import utest.*

object MavenResolverWorkerImplTests extends TestSuite {

  private val mavenCentral = RepoCoord(
    id = "central",
    url = "https://repo.maven.apache.org/maven2",
    `type` = "default"
  )

  private def withWorker[A](f: MavenResolverWorkerImpl => A): A = {
    val dir = os.temp.dir(prefix = "maven-worker-test")
    val worker = MavenResolverWorkerImpl()
    worker.initialize(SessionConfig(localRepositoryPath = dir.toString))
    try f(worker)
    finally {
      worker.close()
      os.remove.all(dir)
    }
  }

  private def dep(
      groupId: String,
      artifactId: String,
      version: String,
      configuration: String = "",
      exclusions: Seq[(String, String)] = Nil,
      classifier: String = "",
      tpe: String = ""
  ): DependencyCoord =
    DependencyCoord(groupId, artifactId, version, configuration, exclusions, classifier, tpe)

  val tests: Tests = Tests {

    test("resolveCommonsLang3") {
      withWorker { worker =>
        val paths = worker.resolveClasspath(
          deps = Seq(dep("org.apache.commons", "commons-lang3", "3.14.0")),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(paths.nonEmpty)
        assert(paths.exists(_.contains("commons-lang3")))
        assert(paths.exists(_.endsWith(".jar")))
      }
    }

    test("resolveWithSources") {
      withWorker { worker =>
        val paths = worker.resolveClasspath(
          deps = Seq(dep("org.apache.commons", "commons-lang3", "3.14.0")),
          repositories = Seq(mavenCentral),
          sources = true,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(paths.nonEmpty)
        assert(paths.exists(p => p.contains("commons-lang3") && p.contains("sources")))
      }
    }

    test("resolveWithExclusions") {
      withWorker { worker =>
        // commons-text:1.11.0 depends on commons-lang3:3.14.0
        val paths = worker.resolveClasspath(
          deps = Seq(dep(
            "org.apache.commons", "commons-text", "1.11.0",
            exclusions = Seq(("org.apache.commons", "commons-lang3"))
          )),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(paths.nonEmpty)
        assert(paths.exists(_.contains("commons-text")))
        // commons-lang3 should be excluded
        assert(!paths.exists(_.contains("commons-lang3")))
      }
    }

    test("resolveWithForceVersions") {
      withWorker { worker =>
        // commons-text:1.11.0 normally pulls commons-lang3:3.14.0
        // Force it to 3.13.0
        val paths = worker.resolveClasspath(
          deps = Seq(dep("org.apache.commons", "commons-text", "1.11.0")),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map("org.apache.commons:commons-lang3" -> "3.13.0"),
          bomDeps = Nil
        )
        assert(paths.nonEmpty)
        assert(paths.exists(_.contains("commons-lang3-3.13.0")))
        assert(!paths.exists(_.contains("commons-lang3-3.14.0")))
      }
    }

    test("resolveMultipleDeps") {
      withWorker { worker =>
        val paths = worker.resolveClasspath(
          deps = Seq(
            dep("org.apache.commons", "commons-lang3", "3.14.0"),
            dep("commons-codec", "commons-codec", "1.16.0")
          ),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(paths.size >= 2)
        assert(paths.exists(_.contains("commons-lang3")))
        assert(paths.exists(_.contains("commons-codec")))
      }
    }

    test("resolveNonExistentArtifact") {
      withWorker { worker =>
        val result = scala.util.Try {
          worker.resolveClasspath(
            deps = Seq(dep("com.example.nonexistent", "no-such-artifact", "99.99.99")),
            repositories = Seq(mavenCentral),
            sources = false,
            forceVersions = Map.empty,
            bomDeps = Nil
          )
        }
        assert(result.isFailure)
        assert(result.failed.get.isInstanceOf[RuntimeException])
        assert(result.failed.get.getMessage.startsWith("[maven-resolver]"))
      }
    }

    test("initializeResolveCloseLifecycle") {
      val dir = os.temp.dir(prefix = "maven-worker-lifecycle-test")
      try {
        val worker = MavenResolverWorkerImpl()
        worker.initialize(SessionConfig(localRepositoryPath = dir.toString))
        val paths = worker.resolveClasspath(
          deps = Seq(dep("javax.inject", "javax.inject", "1")),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(paths.nonEmpty)
        worker.close()
        // After close, resolveClasspath should fail
        val result = scala.util.Try {
          worker.resolveClasspath(
            deps = Seq(dep("javax.inject", "javax.inject", "1")),
            repositories = Seq(mavenCentral),
            sources = false,
            forceVersions = Map.empty,
            bomDeps = Nil
          )
        }
        assert(result.isFailure)
        assert(result.failed.get.isInstanceOf[IllegalStateException])
        assert(result.failed.get.getMessage.contains("[maven-resolver]"))
      } finally os.remove.all(dir)
    }

    test("resolveArtifactsPopulatesAllFields") {
      withWorker { worker =>
        val artifacts = worker.resolveArtifacts(
          deps = Seq(dep("org.apache.commons", "commons-lang3", "3.14.0")),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(artifacts.nonEmpty)
        val lang3 = artifacts.find(_.artifactId == "commons-lang3").get
        assert(lang3.groupId == "org.apache.commons")
        assert(lang3.version == "3.14.0")
        assert(lang3.path.endsWith(".jar"))
        assert(os.exists(os.Path(lang3.path)))
      }
    }

    test("resolveArtifactsPopulatesRepositoryUrl") {
      withWorker { worker =>
        val artifacts = worker.resolveArtifacts(
          deps = Seq(dep("org.apache.commons", "commons-lang3", "3.14.0")),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(artifacts.nonEmpty)
        // At least one artifact should have a non-empty repositoryUrl
        // (either from remote download or local cache)
        val lang3 = artifacts.find(_.artifactId == "commons-lang3").get
        // repositoryUrl may be empty if served from local repo, but the field exists
        // For a fresh temp dir, it should come from the remote
        assert(lang3.repositoryUrl.nonEmpty)
        assert(lang3.repositoryUrl.contains("repo.maven.apache.org") || lang3.repositoryUrl.nonEmpty)
      }
    }

    test("resolveArtifactsWithSources") {
      withWorker { worker =>
        val artifacts = worker.resolveArtifacts(
          deps = Seq(dep("org.apache.commons", "commons-lang3", "3.14.0")),
          repositories = Seq(mavenCentral),
          sources = true,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(artifacts.nonEmpty)
        assert(artifacts.exists(a => a.classifier == "sources"))
        assert(artifacts.exists(a => a.path.contains("sources")))
      }
    }

    test("lastResolvedArtifactsReturnsCachedResults") {
      withWorker { worker =>
        // Before any resolution, lastResolvedArtifacts returns empty
        assert(worker.lastResolvedArtifacts().isEmpty)

        // Resolve and check cache is populated
        val artifacts = worker.resolveArtifacts(
          deps = Seq(dep("org.apache.commons", "commons-lang3", "3.14.0")),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        val cached = worker.lastResolvedArtifacts()
        assert(cached == artifacts)

        // Second resolution updates the cache
        val artifacts2 = worker.resolveArtifacts(
          deps = Seq(dep("commons-codec", "commons-codec", "1.16.0")),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        val cached2 = worker.lastResolvedArtifacts()
        assert(cached2 == artifacts2)
        assert(cached2 != cached)
      }
    }

    // --- resolveMetadata tests (ticket 012) ---

    test("resolveMetadataTransitiveDeps") {
      withWorker { worker =>
        // commons-text:1.11.0 depends on commons-lang3
        val metadata = worker.resolveMetadata(
          deps = Seq(dep("org.apache.commons", "commons-text", "1.11.0")),
          repositories = Seq(mavenCentral),
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        // transitiveDeps maps each dep to its direct children
        val textKey = "org.apache.commons:commons-text:1.11.0"
        assert(metadata.transitiveDeps.contains(textKey))
        val children = metadata.transitiveDeps(textKey)
        assert(children.exists(d => d.artifactId == "commons-lang3"))
      }
    }

    test("resolveMetadataOrderedDeps") {
      withWorker { worker =>
        val metadata = worker.resolveMetadata(
          deps = Seq(dep("org.apache.commons", "commons-text", "1.11.0")),
          repositories = Seq(mavenCentral),
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        // orderedDeps contains all resolved deps
        assert(metadata.orderedDeps.nonEmpty)
        assert(metadata.orderedDeps.exists(_.artifactId == "commons-text"))
        assert(metadata.orderedDeps.exists(_.artifactId == "commons-lang3"))
        // commons-text should come before commons-lang3 (DFS order)
        val textIdx = metadata.orderedDeps.indexWhere(_.artifactId == "commons-text")
        val langIdx = metadata.orderedDeps.indexWhere(_.artifactId == "commons-lang3")
        assert(textIdx < langIdx)
      }
    }

    test("resolveMetadataProjectCacheLicenses") {
      withWorker { worker =>
        // gson has Apache License 2.0 directly in its POM
        val metadata = worker.resolveMetadata(
          deps = Seq(dep("com.google.code.gson", "gson", "2.11.0")),
          repositories = Seq(mavenCentral),
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        val key = "com.google.code.gson:gson:2.11.0"
        assert(metadata.projectCache.contains(key))
        val proj = metadata.projectCache(key)
        assert(proj.licenses.nonEmpty)
        assert(proj.licenses.exists((name, _) => name.contains("Apache")))
      }
    }

    test("resolveMetadataProjectCacheParent") {
      withWorker { worker =>
        // commons-lang3 has a parent POM (commons-parent)
        val metadata = worker.resolveMetadata(
          deps = Seq(dep("org.apache.commons", "commons-lang3", "3.14.0")),
          repositories = Seq(mavenCentral),
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        val key = "org.apache.commons:commons-lang3:3.14.0"
        val proj = metadata.projectCache(key)
        assert(proj.parent.isDefined)
        val (pg, pa, _) = proj.parent.get
        assert(pg == "org.apache.commons")
        assert(pa == "commons-parent")
      }
    }

    test("resolveMetadataProjectCacheOverrides") {
      withWorker { worker =>
        // commons-text inherits dependency management from commons-parent
        // ArtifactDescriptorRequest reads the effective POM, so overrides are present
        val metadata = worker.resolveMetadata(
          deps = Seq(dep("org.apache.commons", "commons-text", "1.11.0")),
          repositories = Seq(mavenCentral),
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        val textKey = "org.apache.commons:commons-text:1.11.0"
        assert(metadata.projectCache.contains(textKey))
        val proj = metadata.projectCache(textKey)
        // commons-text inherits dep mgmt from commons-parent (junit, etc.)
        assert(proj.overrides.nonEmpty)
      }
    }

    test("lastResolvedArtifactsClearedOnReinitialize") {
      val dir = os.temp.dir(prefix = "maven-worker-reinit-test")
      try {
        val worker = MavenResolverWorkerImpl()
        worker.initialize(SessionConfig(localRepositoryPath = dir.toString))
        worker.resolveArtifacts(
          deps = Seq(dep("javax.inject", "javax.inject", "1")),
          repositories = Seq(mavenCentral),
          sources = false,
          forceVersions = Map.empty,
          bomDeps = Nil
        )
        assert(worker.lastResolvedArtifacts().nonEmpty)

        // Re-initialize clears the cache
        worker.initialize(SessionConfig(localRepositoryPath = dir.toString))
        assert(worker.lastResolvedArtifacts().isEmpty)
        worker.close()
      } finally os.remove.all(dir)
    }
  }
}
