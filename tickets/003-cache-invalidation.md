# TICKET-003: Cache invalidation for resolver backend switch

**Stage:** 1 — Configuration plumbing
**Depends on:** 001

## Problem

When a user switches between `coursier` and `maven` backends, all cached resolution
results must be invalidated. Otherwise stale artifacts from the previous backend would
be reused, producing incorrect or insecure builds.

Mill's selective execution system also needs to know about backend changes so it can
re-run affected tasks.

## What needs to be done

1. Add `millResolverBackend: String = ""` field to `Cached` case class
2. Add `millResolverBackend: String = ""` field to `SelectiveExecution.Metadata`
3. Add invalidation check in `GroupExecution.scala` following the `millJvmVersion` pattern
4. Add invalidation check in `SelectiveExecutionImpl.scala` following the `millJvmVersion` pattern

## Verification

- [ ] After switching from `coursier` to `maven` (or vice versa), previously cached
      resolution tasks are re-evaluated (not served from cache)
- [ ] Switching back to the original backend also invalidates (not a no-op)
- [ ] When backend hasn't changed, caching works as before (no unnecessary re-evaluation)
- [ ] `./mill __.compile` passes
- [ ] Existing selective execution tests pass

## Reference

- Design: Section 2.6
- Pattern to follow: `millJvmVersion` invalidation
- Key files:
  - `core/api/src/mill/api/internal/Cached.scala`
  - `core/api/src/mill/api/SelectiveExecution.scala`
  - `core/exec/src/mill/exec/GroupExecution.scala`
  - `core/eval/src/mill/eval/SelectiveExecutionImpl.scala`
