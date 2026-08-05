package com.gtnewhorizons.gtnhgradle.fullpack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Locates exact production SRG artifacts published by another local GTNH checkout. */
public final class MavenLocalProductionArtifactLocator {

    private static final String OBFUSCATION_ATTRIBUTE = "com.gtnewhorizons.retrofuturagradle.obfuscation";

    private MavenLocalProductionArtifactLocator() {}

    public static List<FullPackDependencyOverlayPlanner.Artifact> find(Path repository,
        List<FullPackManifest.MavenModule> requestedModules) {
        final List<FullPackDependencyOverlayPlanner.Artifact> artifacts = new ArrayList<>();
        for (FullPackManifest.MavenModule module : requestedModules) {
            final Path versionDirectory = moduleDirectory(repository, module);
            final String artifactName = module.name() + "-" + module.version();
            final Path moduleMetadata = versionDirectory.resolve(artifactName + ".module");
            final Path productionJar = versionDirectory.resolve(artifactName + ".jar");
            if (!Files.isRegularFile(moduleMetadata) || !Files.isRegularFile(productionJar)) {
                continue;
            }
            if (declaresSrgProductionJar(
                moduleMetadata,
                productionJar.getFileName()
                    .toString())) {
                artifacts.add(
                    new FullPackDependencyOverlayPlanner.Artifact(
                        module,
                        productionJar,
                        FullPackDependencyOverlayPlanner.Source.MAVEN_LOCAL));
            }
        }
        return List.copyOf(artifacts);
    }

    private static Path moduleDirectory(Path repository, FullPackManifest.MavenModule module) {
        Path result = repository.toAbsolutePath()
            .normalize();
        for (String groupSegment : module.group()
            .split("\\.")) {
            result = resolveCoordinateSegment(result, groupSegment, "group");
        }
        result = resolveCoordinateSegment(result, module.name(), "name");
        return resolveCoordinateSegment(result, module.version(), "version");
    }

    private static Path resolveCoordinateSegment(Path parent, String segment, String field) {
        if (segment == null || segment.isBlank()
            || segment.equals(".")
            || segment.equals("..")
            || segment.contains("/")
            || segment.contains("\\")) {
            throw new IllegalArgumentException("Invalid Maven module " + field + ": " + segment);
        }
        return parent.resolve(segment);
    }

    private static boolean declaresSrgProductionJar(Path moduleMetadata, String productionJarName) {
        final JsonObject metadata;
        try {
            metadata = JsonParser.parseString(Files.readString(moduleMetadata, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Maven Local module metadata: " + moduleMetadata, e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid Maven Local module metadata: " + moduleMetadata, e);
        }

        final JsonElement variants = metadata.get("variants");
        if (variants == null || !variants.isJsonArray()) {
            return false;
        }
        for (JsonElement variantElement : variants.getAsJsonArray()) {
            if (!variantElement.isJsonObject()) {
                continue;
            }
            final JsonObject variant = variantElement.getAsJsonObject();
            if (!"reobfElements".equals(text(variant, "name"))) {
                continue;
            }
            final JsonObject attributes = variant.getAsJsonObject("attributes");
            if (attributes == null || !"srg".equals(text(attributes, OBFUSCATION_ATTRIBUTE))) {
                continue;
            }
            final JsonElement files = variant.get("files");
            if (files != null && files.isJsonArray()) {
                for (JsonElement fileElement : files.getAsJsonArray()) {
                    if (fileElement.isJsonObject()
                        && productionJarName.equals(text(fileElement.getAsJsonObject(), "name"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String text(JsonObject object, String field) {
        final JsonElement value = object.get(field);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }
}
