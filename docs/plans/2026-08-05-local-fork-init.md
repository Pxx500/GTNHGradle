# Local Fork Init Script Implementation Plan

**Goal:** Make this fork available automatically to local GTNH mod builds without changing their repositories.
**Scope:** A Windows installer, its generated Gradle init script, and concise developer instructions. No repository-scanning or tracked-file rewrites.
**Approach:** Generate a user-level Gradle init script containing the clone's absolute path. The init script detects exact GTNH convention plugin applications before adding the composite build.
**Constraints:** Leave unrelated Gradle builds and this fork alone. Re-running the installer must update the same global file.
**Acceptance criteria:** GTNH Kotlin and Groovy builds expose `runFullPack`; an unrelated build configures normally; tested repositories remain clean.

### Task 1: Install the scoped global override

**Purpose:** Provide a one-command setup that works from any clone location.
**Affected areas:** `install-local-fork.ps1`, `README.MD`
**Requirements:** Write `~/.gradle/init.d/pxx500-gtnhgradle.gradle`; detect exact `gtnhconvention` or `gtnhsettingsconvention` plugin applications; skip this fork; print the installed path and removal command.
**Verification:** Run the installer twice and confirm the generated file is valid and unchanged on the second run.
**Commit boundary:** `Install the local fork globally`

### Task 2: Verify build selection

**Purpose:** Prove the global override is selective and the documented workflow works.
**Affected areas:** Local test repositories only; no tracked changes.
**Requirements:** Remove the temporary GT5 `includeBuild`, then verify `runFullPack` in one Kotlin and one Groovy GTNH build. Configure a minimal unrelated Gradle project and confirm it does not load the fork.
**Verification:** Run all Gradle checks through Context Mode with explicit timeouts and confirm each tested repository is clean afterward.
**Dependencies:** Task 1.
