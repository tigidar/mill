package mill.javalib.maven.resolver.core

import utest.*
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository

import scala.jdk.CollectionConverters.*

object MavenResolverEngineTests extends TestSuite {

  private def withTempDir[A](f: os.Path => A): A = {
    val dir = os.temp.dir(prefix = "maven-resolver-test")
    try f(dir)
    finally os.remove.all(dir)
  }

  private def defaultConfig(localRepoPath: String): SessionConfig =
    SessionConfig(localRepositoryPath = localRepoPath)

  val tests: Tests = Tests {

    test("createEngineWithDefaultConfig") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(defaultConfig(dir.toString))
        try {
          assert(engine.system != null)
          assert(engine.session != null)
        } finally engine.close()
      }
    }

    test("checksumPolicyApplied") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(
          SessionConfig(localRepositoryPath = dir.toString, checksumPolicy = "fail")
        )
        try {
          assert(engine.session.getChecksumPolicy == "fail")
        } finally engine.close()
      }
    }

    test("checksumPolicyWarn") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(defaultConfig(dir.toString))
        try {
          assert(engine.session.getChecksumPolicy == "warn")
        } finally engine.close()
      }
    }

    test("tlsProtocolsApplied") {
      withTempDir { dir =>
        val config = SessionConfig(
          localRepositoryPath = dir.toString,
          tlsProtocols = "TLSv1.3"
        )
        val engine = MavenResolverEngine(config)
        try {
          val prop = engine.session.getConfigProperties.get("https.protocols")
          assert(prop == "TLSv1.3")
        } finally engine.close()
      }
    }

    test("tlsProtocolsDefault") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(defaultConfig(dir.toString))
        try {
          val prop = engine.session.getConfigProperties.get("https.protocols")
          assert(prop == "TLSv1.2,TLSv1.3")
        } finally engine.close()
      }
    }

    test("offlineMode") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(
          SessionConfig(localRepositoryPath = dir.toString, offline = true)
        )
        try {
          assert(engine.session.isOffline)
        } finally engine.close()
      }
    }

    test("onlineByDefault") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(defaultConfig(dir.toString))
        try {
          assert(!engine.session.isOffline)
        } finally engine.close()
      }
    }

    test("sessionReadOnly") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(defaultConfig(dir.toString))
        try {
          // Session is read-only — mutation should throw
          val result = scala.util.Try {
            engine.session.asInstanceOf[DefaultRepositorySystemSession].setOffline(true)
          }
          assert(result.isFailure)
          assert(result.failed.get.isInstanceOf[IllegalStateException])
        } finally engine.close()
      }
    }

    test("resolveJavaxInject") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(defaultConfig(dir.toString))
        try {
          // javax.inject:javax.inject:1 is a tiny artifact with zero transitive deps
          val artifact = DefaultArtifact("javax.inject:javax.inject:1")
          val dependency = Dependency(artifact, "compile")
          val collectRequest = CollectRequest()
          collectRequest.setRoot(dependency)
          collectRequest.addRepository(
            RemoteRepository.Builder(
              "central",
              "default",
              "https://repo.maven.apache.org/maven2"
            ).build()
          )
          val result = engine.resolve(collectRequest)
          val artifacts = result.getArtifactResults.asScala
          assert(artifacts.nonEmpty)
          val resolved = artifacts.head.getArtifact
          assert(resolved.getGroupId == "javax.inject")
          assert(resolved.getArtifactId == "javax.inject")
          assert(resolved.getVersion == "1")
          // Verify the JAR was actually downloaded to local repo
          assert(java.nio.file.Files.exists(resolved.getPath))
        } finally engine.close()
      }
    }

    test("updatePolicyApplied") {
      withTempDir { dir =>
        val engine = MavenResolverEngine(
          SessionConfig(localRepositoryPath = dir.toString, updatePolicy = "always")
        )
        try {
          assert(engine.session.getUpdatePolicy == "always")
        } finally engine.close()
      }
    }
  }
}
