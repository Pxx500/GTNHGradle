# Concurrent full-pack runs implementation plan

**Goal:** Allow a rebuilt `runFullPack` client to start while an older client from the same checkout is still using its previous local JARs.

**Scope:** Version client runtimes by the content of the manifest, local mod, and dependency overlays. Reuse completed identical client runtimes without runtime writes. Remove inactive client runtimes 24 hours after their last use. Keep server runtime behavior unchanged. Do not migrate or copy writable state from older client runtimes.

**Approach:** Extend `FullPackInstaller` so the existing checkout client directory contains one combined content-addressed runtime for each manifest and local input set. A completion marker makes reuse read-only. A short preparation lock serializes concurrent preparation of the same identity. Last-used metadata and runtime leases live beside the runtime so cleanup can remove expired inactive clients without touching a running game. Daily assets continue to come from `FullPackAssetCache` and remain hardlinked.

**Constraints:** No legacy runtime migration. Client retention is exactly 24 hours after last use. Cleanup never touches servers or the asset cache. No changes to `runFullPackServer`. Tests stay in one final commit.

**Acceptance criteria:** A changed local mod or overlay selects a new runtime, an identical build performs no writes inside the runtime, a running old runtime is not modified or cleaned, expired inactive clients are removed, and both TinkersConstruct and GT5 can run through the feature worktree on Windows.

**Risks or decisions:** Every changed local input set starts with fresh writable game state. A runtime may remain beyond 24 hours while Minecraft is using it and will be retried by later cleanup. Preparation and lease metadata remain outside the runtime directories.

### Task 1: Content-address client runtimes

**Purpose:** Stop later local builds from rewriting files used by an older running client without exceeding the Windows working-directory path limit.

**Affected areas:** `FullPackAssetCache`, `FullPackInstaller`.

**Requirements:**

- hash the local mod JAR by content
- hash dependency overlays by manifest path and content in deterministic order
- combine the manifest and local input identity into one digest-length directory below `client`
- keep the client working-directory path within the previous manifest-only path budget
- keep the current server path and preparation behavior unchanged
- mark a client runtime complete only after materialization succeeds
- return a matching completed client runtime before resolving or writing assets
- rebuild an incomplete matching runtime
- serialize preparation of the same client identity across Gradle processes

**Implementation notes:** Reuse the existing SHA-256 owner in `FullPackAssetCache` by adding a streaming path overload. Keep the new client behavior inside `FullPackInstaller`, which already owns runtime identity and materialization.

**Verification:** Run focused installer tests during development, followed by `compileJava`, unit tests, and formatting checks.

**Commit boundary:** Production code only, `isolate full-pack clients by local inputs`.

### Task 2: Remove expired inactive client runtimes

**Purpose:** Bound disk use without deleting a runtime used by an open Minecraft client.

**Affected areas:** `FullPackInstaller`, `FullPackModule`, and a shared runtime lease owner.

**Requirements:**

- update last-used metadata beside the selected runtime
- expire client runtimes after 24 hours without use
- hold a shared lease for the full lifetime of `runFullPack`
- require an exclusive lease before cleanup and skip active runtimes
- load the launcher patch from the selected runtime instead of staging a shared project copy
- scan client runtimes across the shared runs directory
- leave server runtimes and cached assets unchanged
- retry skipped or failed cleanup during later preparation

**Implementation notes:** Use Gradle's shared build-service lifecycle to release the run lease on success or failure. Keep preparation, last-used, and lease files outside the runtime so reuse and cleanup do not rewrite a running game directory.

**Verification:** Exercise fresh, expired, and leased runtime cleanup through the installer seam, then verify the `runFullPack` task holds the lease for the Minecraft process lifetime.

**Dependencies:** Task 1.

**Commit boundary:** Production code only, together with Task 1 if the path and retention changes remain one coherent runtime-lifecycle change.

### Task 3: Protect the runtime identity and retention contracts

**Purpose:** Lock the observed Windows regressions and retention behavior into automated coverage.

**Affected areas:** `FullPackInstallerTest`, with functional coverage only if the existing Gradle TestKit seam adds behavior not covered by installer tests.

**Requirements:**

- identical inputs return the same completed runtime without rematerializing files
- changed local mod content returns a different runtime and leaves the old runtime untouched
- changed overlay content returns a different runtime
- incomplete client runtime is rebuilt
- immutable daily files remain hardlinked where the filesystem supports it
- server runtime path and state behavior stay unchanged
- the client path contains one combined digest rather than two nested digests
- recently used runtimes are retained
- expired inactive runtimes are removed
- expired leased runtimes are retained

**Verification:** Run the focused test class, the full unit suite, Spotless, and the Java PMD audit. Compare PMD findings with `master` and report only new findings as regressions.

**Dependencies:** Tasks 1 and 2.

**Commit boundary:** All tests in the final commit, `test concurrent full-pack client runtimes`.

### Task 4: Verify two real repositories

**Purpose:** Prove the file-lock fix on TinkersConstruct and the Windows path-length fix on GT5 rather than relying only on filesystem fixtures.

**Affected areas:** TinkersConstruct, GT5-Unofficial, and the global full-pack cache. No mod source changes belong to this task.

**Requirements:**

- point TinkersConstruct at this GTNHGradle worktree
- prepare or launch one local build and record its runtime path
- change the local production JAR content through a normal rebuild
- prepare the second client while the first runtime remains in use
- confirm the second runtime path differs and the first runtime files are unchanged
- launch GT5 with `runFullPack` and confirm Minecraft reaches a stable loaded state
- do not terminate a user-owned Minecraft process without permission

**Verification:** Capture both TinkersConstruct runtime paths, the successful locked-runtime prepare, the successful GT5 process launch, and its latest log. Run a final branch status and diff check.

**Dependencies:** Tasks 1 through 3.
