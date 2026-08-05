package com.gtnewhorizons.gtnhgradle.fullpack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.undercouch.gradle.tasks.download.DownloadAction;

/** Downloads and materializes a resolved full-pack client runtime. */
public final class FullPackInstaller {

    private final Path root;
    private final FullPackAssetCache assetCache;

    public FullPackInstaller(Path root, String githubToken, DownloadAction publicDownload,
        DownloadAction githubDownload) {
        this.root = root;
        this.assetCache = new FullPackAssetCache(root, githubToken, publicDownload, githubDownload);
    }

    public Path prepare(FullPackManifest manifest, String currentOwner, Path currentModJar) {
        return prepare(manifest, currentOwner, currentModJar, List.of());
    }

    public Path prepare(FullPackManifest manifest, String currentOwner, Path currentModJar,
        List<FullPackDependencyOverlayPlanner.Overlay> dependencyOverlays) {
        if (currentOwner == null || currentOwner.isBlank()) {
            throw new IllegalArgumentException("Current full-pack asset owner is required");
        }
        if (!Files.isRegularFile(currentModJar)) {
            throw new IllegalArgumentException("Current mod JAR does not exist: " + currentModJar);
        }

        final Path runsRoot = root.resolve("runs")
            .toAbsolutePath()
            .normalize();
        final Path runtime = resolveInside(
            runsRoot,
            sanitize(
                currentOwner) + "/" + checkoutKey(currentModJar) + "/" + "client" + "/" + sanitize(manifest.digest()));
        try {
            Files.createDirectories(runtime);
            final String localJarPath = manifest.files()
                .stream()
                .filter(file -> currentOwner.equalsIgnoreCase(file.owner()))
                .map(FullPackManifest.File::path)
                .findFirst()
                .orElse("mods/" + sanitize(currentOwner) + ".jar");
            final Set<String> overlayPaths = new HashSet<>();
            dependencyOverlays.forEach(overlay -> overlayPaths.add(overlay.manifestPath()));
            final List<FullPackManifest.File> files = manifest.files()
                .stream()
                .filter(
                    file -> file.owner() == null || !file.owner()
                        .equalsIgnoreCase(currentOwner))
                .filter(file -> !overlayPaths.contains(file.path()))
                .toList();
            final List<FullPackManifest.Asset> assets = new ArrayList<>(files);
            assets.addAll(manifest.archives());
            final List<Path> sources = assetCache.resolveAll(assets);
            for (int i = 0; i < files.size(); i++) {
                final FullPackManifest.File file = files.get(i);
                final Path source = sources.get(i);
                final Path destination = resolveInside(runtime, file.path());
                Files.createDirectories(destination.getParent());
                installImmutableFile(source, destination);
            }
            for (int i = 0; i < manifest.archives()
                .size(); i++) {
                final FullPackManifest.Archive archive = manifest.archives()
                    .get(i);
                extractZip(sources.get(files.size() + i), runtime, archive.exclude(), archive.keepExisting());
            }

            installDependencyOverlays(runtime, dependencyOverlays);
            installTextFiles(runtime, manifest.textFiles());

            final Path localJarDestination = resolveInside(runtime, localJarPath);
            Files.createDirectories(localJarDestination.getParent());
            Files.copy(currentModJar, localJarDestination, StandardCopyOption.REPLACE_EXISTING);
            return runtime;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize full-pack runtime", e);
        }
    }

    private static void installDependencyOverlays(Path runtime,
        List<FullPackDependencyOverlayPlanner.Overlay> dependencyOverlays) throws IOException {
        for (FullPackDependencyOverlayPlanner.Overlay overlay : dependencyOverlays) {
            if (!Files.isRegularFile(overlay.source())) {
                throw new IllegalArgumentException(
                    "Full-pack dependency overlay JAR does not exist: " + overlay.source());
            }
            final Path destination = resolveInside(runtime, overlay.manifestPath());
            Files.createDirectories(destination.getParent());
            Files.copy(overlay.source(), destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void installTextFiles(Path runtime, Map<String, String> textFiles) throws IOException {
        for (Map.Entry<String, String> textFile : textFiles.entrySet()) {
            final Path destination = resolveInside(runtime, textFile.getKey());
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, textFile.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static void installImmutableFile(Path source, Path destination) throws IOException {
        Files.deleteIfExists(destination);
        try {
            Files.createLink(destination, source);
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void extractZip(Path source, Path destinationRoot, Iterable<String> exclusions, boolean keepExisting)
        throws IOException {
        Files.createDirectories(destinationRoot);
        final Set<String> excluded = new HashSet<>();
        exclusions.forEach(excluded::add);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry zipEntry;
            while ((zipEntry = zip.getNextEntry()) != null) {
                final String name = zipEntry.getName();
                final Path destination = resolveZipEntry(destinationRoot, name);
                final String canonicalName = destinationRoot.relativize(destination)
                    .toString()
                    .replace('\\', '/');
                if (isExcluded(canonicalName, excluded) || keepExisting && Files.exists(destination)) {
                    continue;
                }
                if (zipEntry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean isExcluded(String path, Set<String> exclusions) {
        for (String excluded : exclusions) {
            if (path.equals(excluded) || path.startsWith(excluded + "/")) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveZipEntry(Path destinationRoot, String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Full-pack ZIP contains an unsafe path: " + name);
        }
        final Path destination = destinationRoot.resolve(name)
            .normalize();
        if (!destination.startsWith(destinationRoot)) {
            throw new IllegalArgumentException("Full-pack ZIP entry escapes the runtime: " + name);
        }
        return destination;
    }

    private static Path resolveInside(Path root, String relativePath) {
        final Path resolved = root.resolve(relativePath)
            .normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Full-pack destination escapes the runtime: " + relativePath);
        }
        return resolved;
    }

    private static String sanitize(String value) {
        final String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("Current full-pack asset owner cannot identify a runtime directory");
        }
        return sanitized;
    }

    private static String checkoutKey(Path currentModJar) {
        final Path checkoutOutput = currentModJar.toAbsolutePath()
            .normalize()
            .getParent();
        return FullPackAssetCache.sha256(checkoutOutput.toString());
    }
}
