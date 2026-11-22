package mill.api

import utest.*

object ResolverTypeTests extends TestSuite {
  val tests: Tests = Tests {
    test("parse") {
      test("maven") {
        assert(ResolverType.parse("maven") == ResolverType.Maven)
      }
      test("mavenCaseInsensitive") {
        assert(ResolverType.parse("Maven") == ResolverType.Maven)
        assert(ResolverType.parse("MAVEN") == ResolverType.Maven)
      }
      test("coursier") {
        assert(ResolverType.parse("coursier") == ResolverType.Coursier)
      }
      test("coursierCaseInsensitive") {
        assert(ResolverType.parse("Coursier") == ResolverType.Coursier)
        assert(ResolverType.parse("COURSIER") == ResolverType.Coursier)
      }
      test("invalidThrows") {
        val result = scala.util.Try(ResolverType.parse("invalid"))
        assert(result.isFailure)
        val ex = result.failed.get.asInstanceOf[IllegalArgumentException]
        assert(ex.getMessage.contains("Valid values"))
      }
    }
    test("current") {
      test("defaultsToCoursier") {
        // When no system property is set, should default to Coursier
        val prev = sys.props.get("mill.resolver.backend")
        try {
          sys.props.remove("mill.resolver.backend")
          assert(ResolverType.current == ResolverType.Coursier)
        } finally {
          prev.foreach(v => sys.props("mill.resolver.backend") = v)
        }
      }
      test("readsMaven") {
        val prev = sys.props.get("mill.resolver.backend")
        try {
          sys.props("mill.resolver.backend") = "maven"
          assert(ResolverType.current == ResolverType.Maven)
        } finally {
          prev match {
            case Some(v) => sys.props("mill.resolver.backend") = v
            case None => sys.props.remove("mill.resolver.backend")
          }
        }
      }
    }
    test("configConstant") {
      assert(mill.constants.ConfigConstants.millResolverBackend == "mill-resolver-backend")
      val all = mill.constants.ConfigConstants.all()
      assert(all.contains("mill-resolver-backend"))
    }
  }
}
