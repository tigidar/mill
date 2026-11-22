# TICKET-007: SettingsXmlReader — settings.xml parsing + encrypted passwords

**Stage:** 2 — resolveClasspath
**Depends on:** 005

## Problem

Enterprise environments use Maven's `settings.xml` for mirrors, authenticated
repositories, proxies, and encrypted credentials. The Maven backend must read and apply
these settings so that resolution works identically to `mvn` commands.

Encrypted password support is critical — many enterprises have deployed Maven 3's
encrypted passwords (`{COQLCE6DU6GtcS5P=}` format), and newer setups use Maven 4's
pluggable dispatchers.

This lives in `maven-resolver-core` so both the worker and launcher can use it.

## What needs to be done

1. Create `SettingsXmlReader` in `libs/javalib/maven-resolver-core/src/`
2. Load settings using Maven Settings Builder:
   - Read user settings (`~/.m2/settings.xml` or configured path)
   - Read global settings (`/etc/maven/settings.xml` or configured path)
   - Merge into effective settings
3. Decrypt encrypted passwords using `plexus-sec-dispatcher:4.1.0`:
   - Maven 3 format: reads `~/.m2/settings-security.xml` for master password
   - Maven 4 format: pluggable dispatchers (env vars, files)
   - Plain-text passwords pass through unchanged
4. Extract and apply to the resolver session:
   - Mirrors (`<mirrors>` with `mirrorOf` patterns)
   - Server credentials (matched by repository ID)
   - Proxies (HTTP/HTTPS with optional auth)
   - Profile repositories (from active profiles)

## Verification

- [ ] Unit test: parse a `settings.xml` with a `<mirror>` entry, verify mirror is extracted
- [ ] Unit test: parse a `settings.xml` with `<server>` plain-text credentials, verify
      username/password are extracted
- [ ] Unit test: parse a `settings.xml` with encrypted password + `settings-security.xml`,
      verify password is decrypted
- [ ] Unit test: parse a `settings.xml` with `<proxy>` entry, verify proxy config extracted
- [ ] Unit test: parse a `settings.xml` with `<profile>` containing `<repository>`, verify
      repository is extracted when profile is active
- [ ] Unit test: missing settings.xml files are handled gracefully (no crash, empty settings)
- [ ] Unit test: `mirrorOf=*` matches all repositories
- [ ] `./mill libs.javalib.maven-resolver-core.compile` passes

## Reference

- Design: Section 4.2, 4.3
- Appendix B: settings.xml features supported in Phase 1
- Password formats: Maven 3 (`{...}` with hardcoded key) and Maven 4 (pluggable dispatchers)
