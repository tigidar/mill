package mill.api

/**
 * Which dependency-resolution backend Mill should use.
 *
 * The active backend is selected via the system property
 * `mill.resolver.backend` (set from the `.mill-resolver-backend`
 * config file or CLI flags). The default is [[Coursier]].
 */
enum ResolverType derives upickle.ReadWriter {
  case Coursier
  case Maven
}

object ResolverType {

  /** Parse a string into a [[ResolverType]], case-insensitively. */
  def parse(s: String): ResolverType =
    s.toLowerCase match {
      case "coursier" => ResolverType.Coursier
      case "maven" => ResolverType.Maven
      case other =>
        val valid = ResolverType.values.map(_.toString.toLowerCase).mkString(", ")
        throw new IllegalArgumentException(
          s"Unknown resolver backend '$other'. Valid values: $valid"
        )
    }

  /**
   * The currently configured resolver backend, read from
   * `sys.props("mill.resolver.backend")`, defaulting to [[Coursier]].
   */
  def current: ResolverType =
    sys.props.get("mill.resolver.backend") match {
      case Some(v) => parse(v)
      case None => ResolverType.Coursier
    }
}
