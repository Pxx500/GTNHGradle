package com.gtnewhorizons.gtnhgradle.fullpack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import de.undercouch.gradle.tasks.download.DownloadAction;

/** A shared cache keyed by the asset URL. */
public final class FullPackAssetCache {

    private static final int HASH_BUFFER_SIZE = 8192;

    private final Path root;
    private final String githubToken;
    private final DownloadAction publicDownload;
    private final DownloadAction githubDownload;

    public FullPackAssetCache(Path root, String githubToken, DownloadAction publicDownload,
        DownloadAction githubDownload) {
        this.root = root;
        this.githubToken = githubToken == null ? "" : githubToken.trim();
        this.publicDownload = publicDownload;
        this.githubDownload = githubDownload;
    }

    public Path resolve(FullPackManifest.Asset asset) {
        return resolveAll(List.of(asset)).get(0);
    }

    public List<Path> resolveAll(List<? extends FullPackManifest.Asset> assets) {
        if (assets.isEmpty()) {
            return List.of();
        }

        final List<? extends FullPackManifest.Asset> missingAssets = assets.stream()
            .filter(
                asset -> !Files.isRegularFile(
                    objectPath(
                        asset.url()
                            .toString())))
            .toList();
        final List<? extends FullPackManifest.Asset> githubAssets = missingAssets.stream()
            .filter(asset -> asset.authentication() == FullPackManifest.Authentication.GITHUB)
            .toList();
        if (!githubAssets.isEmpty() && githubToken.isBlank()) {
            throw new IllegalStateException(
                "GitHub authentication required for full-pack asset " + githubAssets.get(0)
                    .url() + "; configure fullPack.githubToken or GITHUB_TOKEN");
        }

        download(
            missingAssets.stream()
                .filter(asset -> asset.authentication() == FullPackManifest.Authentication.NONE)
                .toList(),
            Map.of(),
            publicDownload);
        download(
            githubAssets,
            Map.of(
                "Accept",
                "application/octet-stream",
                "Authorization",
                "Bearer " + githubToken,
                "X-GitHub-Api-Version",
                "2022-11-28"),
            githubDownload);
        return assets.stream()
            .map(
                asset -> objectPath(
                    asset.url()
                        .toString()))
            .toList();
    }

    private void download(List<? extends FullPackManifest.Asset> assets, Map<String, String> headers,
        DownloadAction download) {
        final List<URI> urls = assets.stream()
            .map(FullPackManifest.Asset::url)
            .distinct()
            .toList();
        if (urls.isEmpty()) {
            return;
        }

        final Path objects = root.resolve("objects")
            .resolve("sha256");
        try {
            Files.createDirectories(objects);
            download.src(urls);
            if (urls.size() == 1) {
                download.dest(
                    objectPath(
                        urls.get(0)
                            .toString())
                        .toFile());
            } else {
                download.dest(objects.toFile());
                download.eachFile(
                    details -> details.setPath(
                        objectRelativePath(
                            details.getSourceURL()
                                .toString())));
            }
            download.header("User-Agent", "GTNHGradle-fullpack");
            download.headers(headers);
            download.connectTimeout(30_000);
            download.readTimeout(15 * 60_000);
            download.overwrite(false);
            download.tempAndMove(true);
            download.execute()
                .get();
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException("Interrupted while downloading full-pack assets", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to download full-pack assets", e.getCause());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to cache full-pack assets", e);
        }
    }

    Path objectPath(String url) {
        return root.resolve("objects")
            .resolve("sha256")
            .resolve(objectRelativePath(url));
    }

    private static String objectRelativePath(String url) {
        final String key = sha256(url);
        return key.substring(0, 2) + "/" + key.substring(2);
    }

    static String sha256(String value) {
        final MessageDigest digest = sha256Digest();
        return java.util.HexFormat.of()
            .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    static String sha256(Path path) throws IOException {
        final MessageDigest digest = sha256Digest();
        try (var input = Files.newInputStream(path)) {
            final byte[] buffer = new byte[HASH_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of()
            .formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
