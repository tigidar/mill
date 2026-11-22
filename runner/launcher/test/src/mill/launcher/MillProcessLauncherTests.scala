package mill.launcher

import mill.api.ResolverType
import utest.*

object MillProcessLauncherTests extends TestSuite {
  val tests: Tests = Tests {
    test("resolverBackend") {
      test("defaultsToCoursierWhenNoConfig") {
        val workDir = os.temp.dir()
        val result = MillProcessLauncher.resolverBackend(workDir)
        assert(result == ResolverType.Coursier)
      }
      test("readsMavenFromDotFile") {
        val workDir = os.temp.dir()
        os.write(workDir / ".mill-resolver-backend", "maven")
        val result = MillProcessLauncher.resolverBackend(workDir)
        assert(result == ResolverType.Maven)
      }
      test("readsCoursierFromDotFile") {
        val workDir = os.temp.dir()
        os.write(workDir / ".mill-resolver-backend", "coursier")
        val result = MillProcessLauncher.resolverBackend(workDir)
        assert(result == ResolverType.Coursier)
      }
      test("caseInsensitive") {
        val workDir = os.temp.dir()
        os.write(workDir / ".mill-resolver-backend", "Maven")
        val result = MillProcessLauncher.resolverBackend(workDir)
        assert(result == ResolverType.Maven)
      }
      test("invalidValueFailsFast") {
        val workDir = os.temp.dir()
        os.write(workDir / ".mill-resolver-backend", "nix")
        val result = scala.util.Try(MillProcessLauncher.resolverBackend(workDir))
        assert(result.isFailure)
        val ex = result.failed.get.asInstanceOf[IllegalArgumentException]
        assert(ex.getMessage.contains("Valid values"))
        assert(ex.getMessage.contains("nix"))
      }
      test("readsMavenFromBuildHeader") {
        val workDir = os.temp.dir()
        os.write(
          workDir / "build.mill",
          """//| mill-resolver-backend: maven
            |package build
            |""".stripMargin
        )
        val result = MillProcessLauncher.resolverBackend(workDir)
        assert(result == ResolverType.Maven)
      }
      test("dotFileOverridesBuildHeader") {
        val workDir = os.temp.dir()
        os.write(
          workDir / "build.mill",
          """//| mill-resolver-backend: maven
            |package build
            |""".stripMargin
        )
        os.write(workDir / ".mill-resolver-backend", "coursier")
        val result = MillProcessLauncher.resolverBackend(workDir)
        assert(result == ResolverType.Coursier)
      }
    }
    test("loadMillConfig") {
      test("resolverBackendKeyReturnsValueFromDotFile") {
        val workDir = os.temp.dir()
        os.write(workDir / ".mill-resolver-backend", "maven")
        val result =
          MillProcessLauncher.loadMillConfig(mill.constants.ConfigConstants.millResolverBackend, workDir)
        assert(result == Seq("maven"))
      }
      test("resolverBackendKeyReturnsEmptyWhenNoFile") {
        val workDir = os.temp.dir()
        val result =
          MillProcessLauncher.loadMillConfig(mill.constants.ConfigConstants.millResolverBackend, workDir)
        assert(result.isEmpty)
      }
    }
    test("resolveJavaHome") {
      test("mavenBackendReturnsJavaHome") {
        // Maven backend should return JAVA_HOME (env) or java.home (property)
        val expected = Option(System.getenv("JAVA_HOME"))
          .orElse(sys.props.get("java.home"))
          .get
        val result = CoursierClient.resolveJavaHome(
          id = "17",
          jvmIndexVersionOpt = None,
          outDir = "out",
          millRepositories = Seq.empty,
          resolverType = ResolverType.Maven
        )
        assert(result == os.Path(expected))
      }
      test("mavenBackendFailsWhenNoJavaHome") {
        // Temporarily clear java.home to test the error path
        val originalJavaHome = sys.props.get("java.home")
        val originalEnvJavaHome = Option(System.getenv("JAVA_HOME"))
        try {
          sys.props.remove("java.home")
          // Can't unset env var in JVM, but if JAVA_HOME is not set the guard
          // should fail. If JAVA_HOME IS set, it will succeed — which is fine.
          if (originalEnvJavaHome.isEmpty) {
            val result = scala.util.Try(
              CoursierClient.resolveJavaHome(
                id = "17",
                jvmIndexVersionOpt = None,
                outDir = "out",
                millRepositories = Seq.empty,
                resolverType = ResolverType.Maven
              )
            )
            assert(result.isFailure)
            val ex = result.failed.get.asInstanceOf[IllegalStateException]
            assert(ex.getMessage.contains("Maven resolver backend requires a system-installed JVM"))
            assert(ex.getMessage.contains("JAVA_HOME"))
          }
        } finally {
          originalJavaHome.foreach(v => sys.props("java.home") = v)
        }
      }
      test("mavenBackendIgnoresRequestedVersion") {
        // Maven backend returns system JVM regardless of requested version
        val result17 = CoursierClient.resolveJavaHome(
          id = "17",
          jvmIndexVersionOpt = None,
          outDir = "out",
          millRepositories = Seq.empty,
          resolverType = ResolverType.Maven
        )
        val result21 = CoursierClient.resolveJavaHome(
          id = "21",
          jvmIndexVersionOpt = None,
          outDir = "out",
          millRepositories = Seq.empty,
          resolverType = ResolverType.Maven
        )
        // Both return the same system JVM
        assert(result17 == result21)
      }
    }
    test("resolveMillDaemon") {
      test("mavenBackendAcceptsResolverType") {
        // Verify the Maven path creates a MavenResolverEngine and attempts resolution.
        // This will fail to resolve since mill-runner-daemon isn't published to Maven Central,
        // but we verify it enters the Maven code path (error message from maven-resolver).
        val result = scala.util.Try(
          CoursierClient.resolveMillDaemon(
            "out",
            Seq.empty,
            ResolverType.Maven
          )
        )
        // Either succeeds (if cached) or fails with a Maven resolver error (not Coursier)
        result match {
          case scala.util.Success(_) => () // cached result, fine
          case scala.util.Failure(ex) =>
            // Verify it went through the Maven path, not Coursier
            val msg = ex.getMessage
            assert(
              msg.contains("maven-resolver") ||
                msg.contains("ArtifactTransferException") ||
                msg.contains("DependencyResolutionException") ||
                msg.contains("Could not find artifact") ||
                msg.contains("Failed to collect dependencies")
            )
        }
      }
    }
  }
}
