package com.gtnewhorizons.gtnhgradle.fullpack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenLocalProductionArtifactLocatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exactSrgReobfVariantSelectsTheProductionJarInsteadOfTheDevJar() throws Exception {
        FullPackManifest.MavenModule module = new FullPackManifest.MavenModule(
            "com.github.GTNewHorizons",
            "ModularUI2",
            "2.3.79-1.7.10-local");
        Path versionDirectory = temporaryDirectory.resolve("com/github/GTNewHorizons/ModularUI2/2.3.79-1.7.10-local");
        Files.createDirectories(versionDirectory);
        Path productionJar = Files.writeString(versionDirectory.resolve("ModularUI2-2.3.79-1.7.10-local.jar"), "srg");
        Files.writeString(versionDirectory.resolve("ModularUI2-2.3.79-1.7.10-local-dev.jar"), "mcp");
        Files.writeString(versionDirectory.resolve("ModularUI2-2.3.79-1.7.10-local.module"), """
            {
              "variants": [
                {
                  "name": "runtimeElements",
                  "attributes": {"com.gtnewhorizons.retrofuturagradle.obfuscation": "mcp"},
                  "files": [{"name": "ModularUI2-2.3.79-1.7.10-local-dev.jar"}]
                },
                {
                  "name": "reobfElements",
                  "attributes": {"com.gtnewhorizons.retrofuturagradle.obfuscation": "srg"},
                  "files": [{"name": "ModularUI2-2.3.79-1.7.10-local.jar"}]
                }
              ]
            }
            """);

        assertEquals(
            List.of(
                new FullPackDependencyOverlayPlanner.Artifact(
                    module,
                    productionJar,
                    FullPackDependencyOverlayPlanner.Source.MAVEN_LOCAL)),
            MavenLocalProductionArtifactLocator.find(temporaryDirectory, List.of(module)));
    }
}
