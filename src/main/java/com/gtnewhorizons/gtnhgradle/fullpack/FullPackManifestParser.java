package com.gtnewhorizons.gtnhgradle.fullpack;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/** Parses full-pack installation plans. */
public final class FullPackManifestParser {

    private static final int SUPPORTED_VERSION = 1;
    private static final Gson GSON = new Gson();

    private FullPackManifestParser() {}

    public static FullPackManifest parse(String json) {
        final ManifestJson parsed;
        try {
            parsed = GSON.fromJson(json, ManifestJson.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Full-pack manifest is not valid JSON", e);
        }
        if (parsed == null) {
            throw new IllegalArgumentException("Full-pack manifest is empty");
        }
        if (parsed.version() != SUPPORTED_VERSION) {
            throw new IllegalArgumentException("Unsupported full-pack manifest version: " + parsed.version());
        }

        final List<FullPackManifest.File> files = new ArrayList<>();
        final List<FileJson> parsedFiles = orEmpty(parsed.files());
        for (int i = 0; i < parsedFiles.size(); i++) {
            final FileJson file = parsedFiles.get(i);
            final String path = normalizePath(file.path(), "files[" + i + "].path");
            final URI url = URI.create(file.url());
            final FullPackManifest.Authentication authentication = authentication(file.authentication());
            validateAuthenticationUrl(authentication, url, "file " + i);
            files.add(new FullPackManifest.File(file.owner(), path, url, parseMaven(file.maven(), i), authentication));
        }

        final List<FullPackManifest.Archive> archives = new ArrayList<>();
        final List<ArchiveJson> parsedArchives = orEmpty(parsed.archives());
        for (int i = 0; i < parsedArchives.size(); i++) {
            final ArchiveJson archive = parsedArchives.get(i);
            final URI url = URI.create(archive.url());
            final FullPackManifest.Authentication authentication = authentication(archive.authentication());
            validateAuthenticationUrl(authentication, url, "archive " + i);
            final List<String> excludes = new ArrayList<>();
            final List<String> parsedExcludes = orEmpty(archive.exclude());
            for (int j = 0; j < parsedExcludes.size(); j++) {
                excludes.add(normalizePath(parsedExcludes.get(j), "archives[" + i + "].exclude[" + j + "]"));
            }
            archives.add(new FullPackManifest.Archive(url, excludes, archive.keepExisting(), authentication));
        }

        final Map<String, String> textFiles = new LinkedHashMap<>();
        if (parsed.textFiles() != null) {
            parsed.textFiles()
                .forEach((path, content) -> textFiles.put(normalizePath(path, "textFiles path"), content));
        }
        return new FullPackManifest(
            FullPackAssetCache.sha256(json),
            List.copyOf(files),
            List.copyOf(archives),
            Map.copyOf(textFiles));
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static FullPackManifest.MavenModule parseMaven(String value, int fileIndex) {
        if (value == null) {
            return null;
        }
        final String[] parts = value.split(":", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException(
                "Invalid Maven coordinates in full-pack file " + fileIndex + ": " + value);
        }
        return new FullPackManifest.MavenModule(parts[0], parts[1], parts[2]);
    }

    private static FullPackManifest.Authentication authentication(FullPackManifest.Authentication authentication) {
        return authentication == null ? FullPackManifest.Authentication.NONE : authentication;
    }

    private static void validateAuthenticationUrl(FullPackManifest.Authentication authentication, URI url,
        String asset) {
        if (authentication == FullPackManifest.Authentication.NONE) {
            return;
        }
        final boolean githubAssetApi = "https".equalsIgnoreCase(url.getScheme())
            && "api.github.com".equalsIgnoreCase(url.getHost())
            && url.getPath()
                .matches("/repos/[^/]+/[^/]+/releases/assets/[0-9]+");
        if (!githubAssetApi) {
            throw new IllegalArgumentException(
                "Full-pack manifest " + asset + " can only use GitHub authentication with the GitHub Assets API");
        }
    }

    static String normalizePath(String value, String field) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Full-pack manifest field " + field + " contains an unsafe path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Full-pack manifest field " + field + " contains an unsafe path");
            }
        }
        return value;
    }

    private record ManifestJson(int version, List<FileJson> files, List<ArchiveJson> archives,
        Map<String, String> textFiles) {}

    private record FileJson(String owner, String path, String url, String maven,
        FullPackManifest.Authentication authentication) {}

    private record ArchiveJson(String url, List<String> exclude, boolean keepExisting,
        FullPackManifest.Authentication authentication) {}
}
