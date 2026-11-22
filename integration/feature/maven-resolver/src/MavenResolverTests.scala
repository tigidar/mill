package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object MavenResolverTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {

    test("resolveAndCompile") - integrationTest { tester =>
      import tester.*
      // Verify that compilation succeeds — default Coursier backend unaffected
      // by the Maven resolver integration changes.
      val compile = eval("foo.compile")
      assert(compile.isSuccess)
    }

    test("resolvedClasspathContainsGson") - integrationTest { tester =>
      import tester.*
      val cp = eval(("show", "foo.compileClasspath"))
      assert(cp.isSuccess)
      val paths = upickle.read[Seq[String]](cp.out)
      assert(paths.exists(_.contains("gson")))
    }

    test("runProducesOutput") - integrationTest { tester =>
      import tester.*
      val run = eval("foo.run")
      assert(run.isSuccess)
      assert(run.out.contains("""["hello","maven-resolver"]"""))
    }

    test("invalidBackendValue") - integrationTest { tester =>
      import tester.*
      // Add an invalid resolver backend header — this is caught by the launcher
      // before any daemon interaction, producing a clear error.
      modifyFile(
        workspacePath / "build.mill",
        content => s"//| mill-resolver-backend: invalid\n$content"
      )

      val result = eval("foo.compile")
      assert(!result.isSuccess)
      val combined = result.out + " " + result.err
      assert(combined.contains("Valid values"))
    }
  }
}
