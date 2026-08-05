# Local GTNHGradle fork setup

## Goal

Use this fork automatically in local GTNH mod repositories without editing their tracked files.

## Design

- `install-local-fork.ps1` writes one generated init script to `~/.gradle/init.d`.
- The generated script points at the clone it was installed from.
- Before Gradle reads a project's settings, the script checks for an actual application of `com.gtnewhorizons.gtnhconvention` or `com.gtnewhorizons.gtnhsettingsconvention`.
- Matching builds get this fork through `pluginManagement.includeBuild`; unrelated Gradle builds and the fork itself are left alone.
- Running the installer again updates the global file. Removing that file disables the override.

## Verification

- A Kotlin-based GTNH mod and a Groovy-based GTNH mod resolve the local fork.
- An unrelated Gradle project does not receive the override.
- No tested repository gains tracked changes.
