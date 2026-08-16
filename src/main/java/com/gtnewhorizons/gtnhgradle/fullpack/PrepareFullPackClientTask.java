package com.gtnewhorizons.gtnhgradle.fullpack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import de.undercouch.gradle.tasks.download.DownloadAction;

/** Resolves a full-pack manifest and materializes a production runtime. */
@DisableCachingByDefault(because = "The remote manifest may change without its URL changing")
public abstract class PrepareFullPackClientTask extends DefaultTask {

    private final DownloadAction manifestDownload;
    private final DownloadAction publicAssetsDownload;
    private final DownloadAction githubAssetsDownload;

    @Inject
    public PrepareFullPackClientTask() {
        manifestDownload = new DownloadAction(getProject(), this);
        publicAssetsDownload = new DownloadAction(getProject(), this);
        githubAssetsDownload = new DownloadAction(getProject(), this);
    }

    @Input
    public abstract Property<String> getManifestUrl();

    @Input
    public abstract Property<String> getRuntimeDirectoryName();

    @Input
    public abstract Property<String> getOwner();

    @Internal
    public abstract Property<String> getGitHubToken();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLocalModJar();

    @Internal
    public abstract DirectoryProperty getCacheDirectory();

    @Input
    public abstract Property<Boolean> getPreferMavenLocal();

    @Input
    public abstract Property<Boolean> getCleanRuntime();

    @Internal
    public abstract DirectoryProperty getMavenLocalRepository();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getProductionOverlayFiles();

    @Input
    public abstract MapProperty<String, String> getProductionOverlayArtifacts();

    @Input
    public abstract ListProperty<String> getRequestedProductionModules();

    @OutputFile
    public abstract RegularFileProperty getRuntimePathFile();

    @TaskAction
    public void prepareClient() {
        final FullPackManifest manifest = FullPackManifestParser.parse(downloadManifest());
        final List<FullPackManifest.MavenModule> requestedModules = getRequestedProductionModules().getOrElse(List.of())
            .stream()
            .map(PrepareFullPackClientTask::parseModule)
            .toList();
        final List<FullPackDependencyOverlayPlanner.Artifact> artifacts = productionArtifacts(requestedModules);
        final List<FullPackDependencyOverlayPlanner.Overlay> overlays = FullPackDependencyOverlayPlanner
            .plan(manifest, artifacts, requestedModules, getPreferMavenLocal().get());
        final Path runtime = new FullPackInstaller(
            getCacheDirectory().getAsFile()
                .get()
                .toPath(),
            getGitHubToken().getOrElse(""),
            publicAssetsDownload,
            githubAssetsDownload).prepare(
                manifest,
                getOwner().get(),
                getLocalModJar().getAsFile()
                    .get()
                    .toPath(),
                overlays,
                getRuntimeDirectoryName().get(),
                getCleanRuntime().get());
        writeRuntimePath(runtime);
        getLogger().lifecycle("Prepared GTNH {} at {}", getRuntimeDirectoryName().get(), runtime);
    }

    private List<FullPackDependencyOverlayPlanner.Artifact> productionArtifacts(
        List<FullPackManifest.MavenModule> requestedModules) {
        final List<FullPackDependencyOverlayPlanner.Artifact> artifacts = new ArrayList<>();
        for (Map.Entry<String, String> entry : getProductionOverlayArtifacts().getOrElse(Map.of())
            .entrySet()) {
            artifacts.add(
                new FullPackDependencyOverlayPlanner.Artifact(
                    parseModule(entry.getKey()),
                    Path.of(entry.getValue()),
                    FullPackDependencyOverlayPlanner.Source.REMOTE));
        }
        if (getPreferMavenLocal().get()) {
            artifacts.addAll(
                MavenLocalProductionArtifactLocator.find(
                    getMavenLocalRepository().getAsFile()
                        .get()
                        .toPath(),
                    requestedModules));
        }
        return List.copyOf(artifacts);
    }

    private static FullPackManifest.MavenModule parseModule(String coordinates) {
        final String[] parts = coordinates.split(":", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Invalid full-pack Maven module coordinates: " + coordinates);
        }
        return new FullPackManifest.MavenModule(parts[0], parts[1], parts[2]);
    }

    private String downloadManifest() {
        final Path destination = getTemporaryDir().toPath()
            .resolve("manifest.json");
        manifestDownload.src(getManifestUrl().get());
        manifestDownload.dest(destination.toFile());
        manifestDownload.header("User-Agent", "GTNHGradle-fullpack");
        manifestDownload.connectTimeout(30_000);
        manifestDownload.readTimeout(2 * 60_000);
        manifestDownload.overwrite(true);
        manifestDownload.tempAndMove(true);
        try {
            manifestDownload.execute(true)
                .get();
            return Files.readString(destination, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new GradleException("Interrupted while downloading the full-pack manifest", e);
        } catch (ExecutionException e) {
            throw new GradleException("Failed to download the full-pack manifest", e.getCause());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to download the full-pack manifest", e);
        }
    }

    private void writeRuntimePath(Path runtime) {
        final Path destination = getRuntimePathFile().getAsFile()
            .get()
            .toPath();
        try {
            Files.createDirectories(destination.getParent());
            Files.writeString(
                destination,
                runtime.toAbsolutePath()
                    .normalize()
                    .toString(),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to record the prepared full-pack runtime", e);
        }
    }
}
