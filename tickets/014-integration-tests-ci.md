# TICKET-014: Integration tests + CI job for Maven backend

**Stage:** 5 — Integration tests
**Depends on:** 010, 011, 012, 013

## Problem

Individual tickets include unit tests for their components, but we need end-to-end
integration tests that verify the entire Maven backend works as a cohesive whole — from
config reading through resolution to build completion. We also need a CI job that runs
a subset of tests with the Maven backend to catch regressions.

## What needs to be done

1. Create `integration.feature.maven-resolver` test suite:
   - Test basic Java project resolution with Maven backend
   - Test Scala cross-version resolution with Maven backend
   - Test settings.xml integration (mirrors, auth) — may need test-local settings.xml
   - Test switching backend invalidates cache (build with coursier, switch to maven,
     verify re-resolution)
   - Test invalid backend value produces clear error
   - Test Ivy repository produces warning (not crash)

2. Run existing example tests with Maven backend:
   - `example.javalib.basic._` with `-Dmill.resolver.backend=maven`
   - `example.scalalib.basic._` with `-Dmill.resolver.backend=maven`

3. Add test infrastructure helper:
   - A way to run tests with a specific resolver backend (system property override)
   - Usable by both the new integration tests and existing test suites

4. Add CI configuration:
   - A CI job that runs the Maven backend test subset
   - Initially: `example.javalib.basic._` + `example.scalalib.basic._` +
     `integration.feature.maven-resolver` + `integration.feature.publish-local`
   - Document how to expand coverage as confidence grows

## Verification

- [ ] `./mill integration.feature.maven-resolver` passes
- [ ] `example.javalib.basic._` passes with Maven backend
- [ ] `example.scalalib.basic._` passes with Maven backend
- [ ] `integration.feature.publish-local` passes with Maven backend
- [ ] CI job runs and passes on the PR
- [ ] Default CI (Coursier backend) is unaffected

## Reference

- Design: Section 11 (Testing Strategy)
- CI strategy: Design Section 11 (CI strategy subsection)
- Test infrastructure: Design Section 11 (test helper)
