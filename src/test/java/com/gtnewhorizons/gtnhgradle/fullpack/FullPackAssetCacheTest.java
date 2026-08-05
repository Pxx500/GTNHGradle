package com.gtnewhorizons.gtnhgradle.fullpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.gradle.testfixtures.ProjectBuilder;

import de.undercouch.gradle.tasks.download.DownloadAction;

class FullPackAssetCacheTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void cachedAssetsAreReturnedInManifestOrder() throws Exception {
        FullPackAssetCache cache = cache("");
        FullPackManifest.File first = file("https://example.invalid/first.jar", FullPackManifest.Authentication.NONE);
        FullPackManifest.File second = file("https://example.invalid/second.jar", FullPackManifest.Authentication.NONE);
        Path firstObject = cache.objectPath(
            first.url()
                .toString());
        Path secondObject = cache.objectPath(
            second.url()
                .toString());
        Files.createDirectories(firstObject.getParent());
        Files.createDirectories(secondObject.getParent());
        Files.writeString(firstObject, "first");
        Files.writeString(secondObject, "second");

        List<Path> resolved = cache.resolveAll(List.of(second, first));

        assertEquals(List.of(secondObject, firstObject), resolved);
        assertNotEquals(firstObject, secondObject);
    }

    @Test
    void authenticatedAssetWithoutATokenFailsBeforeDownloading() {
        FullPackManifest.File file = file(
            "https://api.github.com/repos/GTNewHorizons/Test/releases/assets/1",
            FullPackManifest.Authentication.GITHUB);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> cache("").resolve(file));

        assertTrue(
            error.getMessage()
                .contains("configure fullPack.githubToken or GITHUB_TOKEN"));
    }

    private FullPackAssetCache cache(String githubToken) {
        var project = ProjectBuilder.builder()
            .build();
        return new FullPackAssetCache(
            temporaryDirectory,
            githubToken,
            new DownloadAction(project),
            new DownloadAction(project));
    }

    private static FullPackManifest.File file(String url, FullPackManifest.Authentication authentication) {
        return new FullPackManifest.File(null, "mods/asset.jar", URI.create(url), null, authentication);
    }
}
