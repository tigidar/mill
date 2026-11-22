# TICKET-002: Launcher config reading + daemon propagation

**Stage:** 1 — Configuration plumbing
**Depends on:** 001

## Problem

The launcher (`MillProcessLauncher`) must read the `mill-resolver-backend` config value
at startup — before the Mill daemon exists — and propagate it to the daemon process as a
system property (`-Dmill.resolver.backend=<value>`). This is how the evaluator and
`Jvm.scala` learn which backend is active.

The reading must follow the same pattern used for `mill-jvm-version`: dot-file, build
header (YAML or `//|` syntax), with the established priority order.

## What needs to be done

1. In `MillProcessLauncher`, read `ConfigConstants.millResolverBackend` using the existing
   `loadMillConfig` infrastructure
2. Parse the value to `ResolverType` (via `ResolverType.parse`), defaulting to `Coursier`
3. Pass `-Dmill.resolver.backend=<value>` when spawning the daemon process
4. The `resolverBackend` value must also be available to the launcher itself for later use
   (ticket 013 will use it for daemon resolution)

## Verification

- [ ] With no config file: daemon starts normally, `ResolverType.current` returns `Coursier`
- [ ] With `.mill-resolver-backend` containing `maven`: daemon receives `-Dmill.resolver.backend=maven`
- [ ] With build header `//| mill-resolver-backend: maven`: same result
- [ ] With invalid value (e.g., `nix`): fails fast with clear error before daemon starts
- [ ] `./mill __.compile` passes
- [ ] All existing tests pass (backend defaults to Coursier, zero behavior change)

## Reference

- Design: Section 2.2, 2.3, 2.4
- Pattern to follow: `mill-jvm-version` reading in `MillProcessLauncher`
- Key files: `runner/launcher/src/mill/launcher/MillProcessLauncher.scala`
