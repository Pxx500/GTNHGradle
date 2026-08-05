package com.gtnewhorizons.gtnhgradle.fullpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FullPackManifestParserTest {

    private static final String VALID_PLAN = """
        {
          "version": 1,
          "files": [
            {
              "owner": "GT5-Unofficial",
              "path": "mods/gregtech.jar",
              "url": "https://example.invalid/gregtech.jar",
              "maven": "com.github.GTNewHorizons:GT5-Unofficial:5.09.54.73"
            },
            {
              "path": ".gtnh/launcher/lwjgl3ify-forgePatches.jar",
              "url": "https://example.invalid/lwjgl3ify-forgePatches.jar"
            }
          ],
          "archives": [
            {
              "url": "https://example.invalid/config.zip",
              "exclude": ["server.properties", "journeymap/data"]
            },
            {
              "url": "https://example.invalid/translations.zip",
              "keepExisting": true
            }
          ],
          "textFiles": {
            "config/txloader/load/mainmenu/version.txt": "GT New Horizons 2.8.0\n"
          }
        }
        """;

    @Test
    void compactPlanPreservesInstallationOrderAndOptionalMetadata() {
        FullPackManifest manifest = FullPackManifestParser.parse(VALID_PLAN);

        assertEquals(
            2,
            manifest.files()
                .size());
        assertEquals(
            "GT5-Unofficial",
            manifest.files()
                .getFirst()
                .owner());
        assertEquals(
            "mods/gregtech.jar",
            manifest.files()
                .getFirst()
                .path());
        assertEquals(
            new FullPackManifest.MavenModule("com.github.GTNewHorizons", "GT5-Unofficial", "5.09.54.73"),
            manifest.files()
                .getFirst()
                .maven());
        assertEquals(
            FullPackManifest.Authentication.NONE,
            manifest.files()
                .getFirst()
                .authentication());
        assertEquals(
            List.of("server.properties", "journeymap/data"),
            manifest.archives()
                .getFirst()
                .exclude());
        assertEquals(
            true,
            manifest.archives()
                .get(1)
                .keepExisting());
        assertEquals(
            Map.of("config/txloader/load/mainmenu/version.txt", "GT New Horizons 2.8.0\n"),
            manifest.textFiles());
    }

    @Test
    void unsupportedVersionIsRejected() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> FullPackManifestParser.parse(VALID_PLAN.replace("\"version\": 1", "\"version\": 2")));

        assertEquals("Unsupported full-pack manifest version: 2", error.getMessage());
    }

    @Test
    void pathsCannotEscapeTheRuntime() {
        IllegalArgumentException file = assertThrows(
            IllegalArgumentException.class,
            () -> FullPackManifestParser.parse(VALID_PLAN.replace("mods/gregtech.jar", "../gregtech.jar")));
        IllegalArgumentException text = assertThrows(
            IllegalArgumentException.class,
            () -> FullPackManifestParser
                .parse(VALID_PLAN.replace("config/txloader/load/mainmenu/version.txt", "../version.txt")));
        IllegalArgumentException exclusion = assertThrows(
            IllegalArgumentException.class,
            () -> FullPackManifestParser.parse(VALID_PLAN.replace("journeymap/data", "../data")));

        assertEquals("Full-pack manifest field files[0].path contains an unsafe path", file.getMessage());
        assertEquals("Full-pack manifest field textFiles path contains an unsafe path", text.getMessage());
        assertEquals("Full-pack manifest field archives[0].exclude[1] contains an unsafe path", exclusion.getMessage());
    }

    @Test
    void githubAuthenticationCannotSendATokenToAnotherHost() {
        String authenticated = VALID_PLAN.replace(
            "\"url\": \"https://example.invalid/gregtech.jar\"",
            "\"url\": \"https://example.invalid/gregtech.jar\", \"authentication\": \"github\"");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> FullPackManifestParser.parse(authenticated));

        assertEquals(
            "Full-pack manifest file 0 can only use GitHub authentication with the GitHub Assets API",
            error.getMessage());
    }

    @Test
    void malformedMavenCoordinatesAreRejected() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> FullPackManifestParser.parse(
                VALID_PLAN.replace("com.github.GTNewHorizons:GT5-Unofficial:5.09.54.73", "GT5-Unofficial:5.09.54.73")));

        assertEquals("Invalid Maven coordinates in full-pack file 0: GT5-Unofficial:5.09.54.73", error.getMessage());
    }
}
