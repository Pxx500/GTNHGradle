package com.gtnewhorizons.gtnhgradle.fullpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class FullPackDependencyOverlayPlannerTest {

    @Test
    void explicitlyPreferredMavenLocalArtifactOverridesANewerDailyVersion() {
        FullPackManifest.MavenModule dailyModule = module("2.3.85-1.7.10");
        FullPackManifest manifest = manifest(dailyModule);
        Path localJar = Path.of("ModularUI2-2.3.79-1.7.10-local-power-goggles.jar");
        FullPackDependencyOverlayPlanner.Artifact localArtifact = new FullPackDependencyOverlayPlanner.Artifact(
            module("2.3.79-1.7.10-local-power-goggles"),
            localJar,
            FullPackDependencyOverlayPlanner.Source.MAVEN_LOCAL);

        assertEquals(
            List.of(new FullPackDependencyOverlayPlanner.Overlay("mods/modularui2-daily.jar", localJar)),
            FullPackDependencyOverlayPlanner
                .plan(manifest, List.of(localArtifact), List.of(localArtifact.module()), true));
    }

    @Test
    void newerResolvedProductionArtifactOverridesTheDailyVersion() {
        FullPackManifest manifest = manifest(module("2.3.85-1.7.10"));
        Path resolvedJar = Path.of("ModularUI2-2.3.86-1.7.10.jar");
        FullPackDependencyOverlayPlanner.Artifact resolvedArtifact = new FullPackDependencyOverlayPlanner.Artifact(
            module("2.3.86-1.7.10"),
            resolvedJar,
            FullPackDependencyOverlayPlanner.Source.REMOTE);

        assertEquals(
            List.of(new FullPackDependencyOverlayPlanner.Overlay("mods/modularui2-daily.jar", resolvedJar)),
            FullPackDependencyOverlayPlanner
                .plan(manifest, List.of(resolvedArtifact), List.of(resolvedArtifact.module()), false));
    }

    @Test
    void olderRemoteArtifactDoesNotDowngradeTheDailyVersion() {
        FullPackDependencyOverlayPlanner.Artifact olderArtifact = new FullPackDependencyOverlayPlanner.Artifact(
            module("2.3.79-1.7.10"),
            Path.of("ModularUI2-2.3.79-1.7.10.jar"),
            FullPackDependencyOverlayPlanner.Source.REMOTE);

        assertEquals(
            List.of(),
            FullPackDependencyOverlayPlanner.plan(
                manifest(module("2.3.85-1.7.10")),
                List.of(olderArtifact),
                List.of(olderArtifact.module()),
                false));
    }

    @Test
    void MavenLocalArtifactIsIgnoredWithoutExplicitOptIn() {
        FullPackDependencyOverlayPlanner.Artifact localArtifact = new FullPackDependencyOverlayPlanner.Artifact(
            module("2.3.79-1.7.10-local"),
            Path.of("ModularUI2-2.3.79-1.7.10-local.jar"),
            FullPackDependencyOverlayPlanner.Source.MAVEN_LOCAL);

        assertEquals(
            List.of(),
            FullPackDependencyOverlayPlanner.plan(
                manifest(module("2.3.85-1.7.10")),
                List.of(localArtifact),
                List.of(localArtifact.module()),
                false));
    }

    @Test
    void newerDeclaredModuleWithoutAProductionArtifactFailsInsteadOfSilentlyUsingDaily() {
        FullPackManifest.MavenModule requested = module("2.3.86-1.7.10");

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> FullPackDependencyOverlayPlanner
                .plan(manifest(module("2.3.85-1.7.10")), List.of(), List.of(requested), false));

        assertEquals(
            "Could not resolve a production SRG JAR for com.github.GTNewHorizons:ModularUI2:2.3.86-1.7.10",
            error.getMessage());
    }

    private static FullPackManifest manifest(FullPackManifest.MavenModule module) {
        FullPackManifest.File file = new FullPackManifest.File(
            "ModularUI2",
            "mods/modularui2-daily.jar",
            URI.create("https://example.invalid/modularui2.jar"),
            module,
            FullPackManifest.Authentication.NONE);
        return new FullPackManifest("digest", List.of(file), List.of(), java.util.Map.of());
    }

    private static FullPackManifest.MavenModule module(String version) {
        return new FullPackManifest.MavenModule("com.github.GTNewHorizons", "ModularUI2", version);
    }
}
