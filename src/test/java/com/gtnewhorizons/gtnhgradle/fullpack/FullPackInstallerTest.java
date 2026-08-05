package com.gtnewhorizons.gtnhgradle.fullpack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;

import de.undercouch.gradle.tasks.download.DownloadAction;

class FullPackInstallerTest {

    private final Project project = ProjectBuilder.builder()
        .build();

    @TempDir
    Path temporaryDirectory;

    @Test
    void localModAndDependencyOverlaysReplaceReleasedFilesBeforeTextFilesAreWritten() throws Exception {
        FullPackManifest.MavenModule module = new FullPackManifest.MavenModule("g", "dependency", "1.0");
        FullPackManifest manifest = manifest(
            "digest",
            List.of(
                file("CurrentMod", "/current.jar", "mods/current-release.jar", null),
                file("DependencyMod", "/dependency.jar", "mods/dependency-release.jar", module)),
            List.of(),
            Map.of("config/generated.cfg", "generated", "mods/current-release.jar", "temporary"));
        Path currentJar = Files.write(temporaryDirectory.resolve("current-local.jar"), bytes("local-current"));
        Path dependencyJar = Files.write(temporaryDirectory.resolve("dependency-local.jar"), bytes("local-dependency"));
        FullPackDependencyOverlayPlanner.Overlay overlay = new FullPackDependencyOverlayPlanner.Overlay(
            "mods/dependency-release.jar",
            dependencyJar);

        Path runtime = installer(temporaryDirectory.resolve("fullpack"))
            .prepare(manifest, "CurrentMod", currentJar, List.of(overlay));

        assertFalse(Files.exists(runtime.resolve("mods/current-local.jar")));
        assertArrayEquals(bytes("local-current"), Files.readAllBytes(runtime.resolve("mods/current-release.jar")));
        assertArrayEquals(
            bytes("local-dependency"),
            Files.readAllBytes(runtime.resolve("mods/dependency-release.jar")));
        assertEquals("generated", Files.readString(runtime.resolve("config/generated.cfg")));
        assertTrue(
            runtime.toString()
                .contains("client"));
    }

    @Test
    void archivesExtractInOrderWithDirectoryExclusionsAndKeepExisting() throws Exception {
        byte[] config = zip(
            Map.of(
                "config/client.cfg",
                bytes("config"),
                "config/server/secret.cfg",
                bytes("server"),
                "generated.txt",
                bytes("archive"),
                "shared.txt",
                bytes("first")));
        byte[] translations = zip(Map.of("shared.txt", bytes("second"), "lang/en_US.lang", bytes("translation")));
        FullPackManifest.Archive configArchive = archive("/config.zip", List.of("config/server"), false);
        FullPackManifest.Archive translationsArchive = archive("/translations.zip", List.of(), true);
        FullPackManifest manifest = manifest(
            "archives",
            List.of(),
            List.of(configArchive, translationsArchive),
            Map.of("generated.txt", "text"));
        Path cacheRoot = temporaryDirectory.resolve("fullpack");
        cache(cacheRoot, configArchive, config);
        cache(cacheRoot, translationsArchive, translations);
        Path localJar = Files.write(temporaryDirectory.resolve("mod.jar"), bytes("local"));

        Path runtime = installer(cacheRoot).prepare(manifest, "CurrentMod", localJar);

        assertEquals("config", Files.readString(runtime.resolve("config/client.cfg")));
        assertFalse(Files.exists(runtime.resolve("config/server/secret.cfg")));
        assertEquals("first", Files.readString(runtime.resolve("shared.txt")));
        assertEquals("translation", Files.readString(runtime.resolve("lang/en_US.lang")));
        assertEquals("text", Files.readString(runtime.resolve("generated.txt")));
    }

    @Test
    void zipEntryCannotEscapeTheRuntime() throws Exception {
        FullPackManifest.Archive archive = archive("/unsafe.zip", List.of(), false);
        FullPackManifest manifest = manifest("unsafe", List.of(), List.of(archive), Map.of());
        Path cacheRoot = temporaryDirectory.resolve("fullpack");
        cache(cacheRoot, archive, zip(Map.of("../escaped.txt", bytes("escaped"))));
        Path localJar = Files.write(temporaryDirectory.resolve("mod.jar"), bytes("local"));

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> installer(cacheRoot).prepare(manifest, "CurrentMod", localJar));

        assertTrue(
            error.getMessage()
                .contains("escapes the runtime"));
        assertFalse(Files.exists(temporaryDirectory.resolve("escaped.txt")));
    }

    @Test
    void differentCheckoutsUseIsolatedRuntimeDirectories() throws Exception {
        FullPackManifest manifest = manifest("same-digest", List.of(), List.of(), Map.of());
        Path firstJar = temporaryDirectory.resolve("checkout-one/build/libs/mod.jar");
        Path secondJar = temporaryDirectory.resolve("checkout-two/build/libs/mod.jar");
        Files.createDirectories(firstJar.getParent());
        Files.createDirectories(secondJar.getParent());
        Files.write(firstJar, bytes("first"));
        Files.write(secondJar, bytes("second"));
        FullPackInstaller installer = installer(temporaryDirectory.resolve("fullpack"));

        Path firstRuntime = installer.prepare(manifest, "CurrentMod", firstJar);
        Path secondRuntime = installer.prepare(manifest, "CurrentMod", secondJar);

        assertNotEquals(firstRuntime, secondRuntime);
        assertEquals("first", Files.readString(firstRuntime.resolve("mods/CurrentMod.jar")));
        assertEquals("second", Files.readString(secondRuntime.resolve("mods/CurrentMod.jar")));
    }

    private static FullPackManifest manifest(String digest, List<FullPackManifest.File> files,
        List<FullPackManifest.Archive> archives, Map<String, String> textFiles) {
        return new FullPackManifest(digest, files, archives, textFiles);
    }

    private static FullPackManifest.File file(String owner, String source, String path,
        FullPackManifest.MavenModule maven) {
        return new FullPackManifest.File(
            owner,
            path,
            URI.create("https://example.invalid" + source),
            maven,
            FullPackManifest.Authentication.NONE);
    }

    private static FullPackManifest.Archive archive(String source, List<String> excludes, boolean keepExisting) {
        return new FullPackManifest.Archive(
            URI.create("https://example.invalid" + source),
            excludes,
            keepExisting,
            FullPackManifest.Authentication.NONE);
    }

    private void cache(Path root, FullPackManifest.Asset asset, byte[] content) throws IOException {
        Path object = new FullPackAssetCache(root, "", new DownloadAction(project), new DownloadAction(project))
            .objectPath(
                asset.url()
                    .toString());
        Files.createDirectories(object.getParent());
        Files.write(object, content);
    }

    private FullPackInstaller installer(Path root) {
        return new FullPackInstaller(root, "", new DownloadAction(project), new DownloadAction(project));
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
