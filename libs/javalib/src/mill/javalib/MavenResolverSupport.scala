package mill.javalib

import mill.*
import mill.util.Jvm

private[mill] trait MavenResolverSupport extends mill.api.Module { self: CoursierModule =>

  private def mavenResolverWorkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-maven-resolver-worker")
    ))
  }

  private def mavenResolverWorkerClassloader: Task.Worker[ClassLoader & AutoCloseable] =
    Task.Worker {
      val classPath = mavenResolverWorkerClasspath().map(_.path)
      Jvm.createClassLoader(classPath = classPath, parent = getClass.getClassLoader)
    }

  private[mill] def mavenResolverWorker
      : Task.Worker[mill.javalib.internal.MavenResolverSupport.Api] =
    Task.Worker {
      val worker = mavenResolverWorkerClassloader()
        .loadClass("mill.javalib.maven.resolver.worker.impl.MavenResolverWorkerImpl")
        .getConstructor()
        .newInstance()
        .asInstanceOf[mill.javalib.internal.MavenResolverSupport.Api]
      worker.initialize(mill.javalib.internal.MavenResolverSupport.SessionConfig(
        localRepositoryPath = (os.home / ".m2" / "repository").toString
      ))
      worker
    }
}
