# TICKET-001: ResolverType enum + ConfigConstants + parsing

**Stage:** 1 — Configuration plumbing
**Depends on:** —

## Problem

Mill needs a type-safe way to represent which resolution backend is active. This is the
foundational type that every other ticket depends on. It must be an enum (not a string)
so that `match` expressions produce compiler errors when a new backend is added.

A corresponding config constant is needed so the value can be read from `.mill-resolver-backend`
or build headers, following the same pattern as `mill-jvm-version`.

## What needs to be done

1. Create `ResolverType` enum in `core/api` with cases `Coursier` and `Maven`
2. Add a `parse(s: String): ResolverType` method — case-insensitive, fails fast on invalid input
   with a clear error listing valid options
3. Add a `current: ResolverType` companion method reading from `sys.props("mill.resolver.backend")`
   defaulting to `Coursier`
4. Add `millResolverBackend` constant to `ConfigConstants.java`
5. Register it in `ConfigConstants.all()`

## Verification

- [ ] `ResolverType.parse("maven")` returns `ResolverType.Maven`
- [ ] `ResolverType.parse("Maven")` returns `ResolverType.Maven` (case-insensitive)
- [ ] `ResolverType.parse("coursier")` returns `ResolverType.Coursier`
- [ ] `ResolverType.parse("invalid")` throws with message containing "valid values"
- [ ] `ResolverType.current` returns `Coursier` when no system property is set
- [ ] `ResolverType.current` returns `Maven` when `-Dmill.resolver.backend=maven` is set
- [ ] `ConfigConstants.millResolverBackend` exists and is included in `all()`
- [ ] `./mill __.compile` passes — no existing tests break

## Reference

- Design: Section 2.1, 2.5
- Pattern to follow: `mill-jvm-version` config constant
- Existing names to avoid collision with: `mill.main.maven.Resolver`, `mill.javalib.CoursierModule.Resolver`, `mill.api.Cross.Resolver`
