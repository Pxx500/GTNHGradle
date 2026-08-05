package com.gtnewhorizons.gtnhgradle.fullpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.maven.artifact.versioning.ComparableVersion;

/** Selects production dependency JARs which should replace entries from the daily pack. */
public final class FullPackDependencyOverlayPlanner {

    public enum Source {
        REMOTE,
        MAVEN_LOCAL
    }

    public record Artifact(FullPackManifest.MavenModule module, Path file, Source source) {}

    public record Overlay(String manifestPath, Path source) {}

    private FullPackDependencyOverlayPlanner() {}

    public static List<Overlay> plan(FullPackManifest manifest, List<Artifact> artifacts,
        List<FullPackManifest.MavenModule> requestedModules, boolean preferMavenLocal) {
        final List<Overlay> overlays = new ArrayList<>();
        for (FullPackManifest.File file : manifest.files()) {
            if (file.maven() == null) {
                continue;
            }
            final Artifact selected = artifacts.stream()
                .filter(artifact -> sameModule(file.maven(), artifact.module()))
                .filter(
                    artifact -> artifact.source() == Source.MAVEN_LOCAL ? preferMavenLocal
                        : isSameOrNewer(artifact.module(), file.maven()))
                .max(
                    Comparator.comparing((Artifact artifact) -> artifact.source() == Source.MAVEN_LOCAL)
                        .thenComparing(
                            artifact -> new ComparableVersion(
                                artifact.module()
                                    .version())))
                .orElse(null);
            if (selected != null) {
                overlays.add(new Overlay(file.path(), selected.file()));
                continue;
            }

            final FullPackManifest.MavenModule newestRequested = requestedModules.stream()
                .filter(module -> sameModule(file.maven(), module))
                .max(
                    (first, second) -> new ComparableVersion(first.version())
                        .compareTo(new ComparableVersion(second.version())))
                .orElse(null);
            if (newestRequested != null && isNewer(newestRequested, file.maven())) {
                throw new IllegalStateException(
                    "Could not resolve a production SRG JAR for " + coordinates(newestRequested));
            }
        }
        return List.copyOf(overlays);
    }

    private static boolean sameModule(FullPackManifest.MavenModule first, FullPackManifest.MavenModule second) {
        return first.group()
            .equals(second.group())
            && first.name()
                .equals(second.name());
    }

    private static boolean isSameOrNewer(FullPackManifest.MavenModule candidate,
        FullPackManifest.MavenModule baseline) {
        return new ComparableVersion(candidate.version()).compareTo(new ComparableVersion(baseline.version())) >= 0;
    }

    private static boolean isNewer(FullPackManifest.MavenModule candidate, FullPackManifest.MavenModule baseline) {
        return new ComparableVersion(candidate.version()).compareTo(new ComparableVersion(baseline.version())) > 0;
    }

    private static String moduleKey(FullPackManifest.MavenModule module) {
        return module.group() + ":" + module.name();
    }

    private static String coordinates(FullPackManifest.MavenModule module) {
        return moduleKey(module) + ":" + module.version();
    }
}
