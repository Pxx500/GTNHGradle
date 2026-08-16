# Concurrent full-pack runs

## Goal

Allow a developer to start a new `runFullPack` client while an older client from the same checkout is still running. The older client must keep its original local mods, and the new client must receive the newly built local mods without file-lock failures on Windows.

## Runtime identity

The client runtime is identified by all inputs that affect its mod set:

- daily manifest digest
- local mod JAR content digest
- local dependency overlay paths and content digests

The manifest and local input digests are combined into one runtime directory name. This keeps the Windows working directory within the same path budget as the previous manifest-only runtime. Two prepares with identical inputs reuse the same prepared runtime without rewriting its files. Changed local inputs produce a new sibling runtime. Server runtime behavior is unchanged so existing server worlds remain in their stable directory.

The global asset cache remains the source of daily pack files. Prepared runtimes hardlink immutable daily files from that cache, so a new local build does not download or duplicate the full pack.

## Runtime state

The asset cache is never used as a Minecraft working directory. The first client launch already uses a derived runtime, so a running game cannot lock the cache used to prepare later runtimes.

A new local input set starts with fresh runtime state. Worlds, settings, configs, logs, and other writable game data are not copied from older runtimes. An identical build may reuse its already prepared runtime without writing inside it. Its last-used timestamp is stored beside the runtime. Concurrent clients using identical inputs therefore share that working directory, with the same limitations as launching Minecraft twice from one game directory.

## Runtime retention

Completed client runtimes expire 24 hours after their last use. Preparation updates the selected runtime's last-used timestamp, then removes expired client runtimes across the shared full-pack runs directory.

`runFullPack` holds a shared lease beside its runtime while Minecraft is running. Cleanup requires an exclusive lease and skips a runtime when another Gradle process still holds it. Cleanup never touches server runtimes or the global asset cache. Failed cleanup is retried by a later preparation.

The client loads its launcher patch directly from the selected runtime. Preparing another client therefore does not overwrite a launcher file used by an existing process.

## Preparation flow

1. Build the local production JAR and resolve local dependency overlays.
2. Download and parse the daily manifest.
3. Calculate one runtime identity from the manifest and local input contents.
4. Return an already completed matching runtime without touching its files.
5. Otherwise create a new sibling runtime.
6. Materialize daily files from the global asset cache and apply archives, text files, dependency overlays, and the local mod JAR.
7. Mark the runtime complete only after every file has been installed.
8. Record the runtime's last use and remove inactive client runtimes unused for 24 hours.
9. Write the selected runtime path for `runFullPack`, acquire its shared lease, and launch Minecraft there.

An incomplete runtime is never reused. A later prepare may rebuild it from scratch.

## Verification

Automated coverage must prove that:

- identical inputs return the same runtime and do not rewrite locked immutable files
- changing the local mod JAR selects a different runtime
- changing a dependency overlay selects a different runtime
- immutable daily files are still hardlinked from the asset cache
- a changed input set starts with fresh writable runtime state
- an incomplete runtime is not reused
- server runtime identity and cleanup behavior remain unchanged
- the client runtime path does not gain another digest-length path segment
- a runtime used within 24 hours is retained
- an expired inactive client runtime is removed
- an expired active client runtime is skipped

The integration check starts one client build, rebuilds the local mod, and prepares a second client while the first remains open. The second preparation must complete without modifying the first runtime. A second repository must also launch through `runFullPack` on Windows to prove that the combined runtime identity stays within the process working-directory limit.
