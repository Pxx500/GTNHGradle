package com.gtnewhorizons.gtnhgradle.modules;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.jetbrains.annotations.NotNull;

import com.gtnewhorizons.gtnhgradle.GTNHGradlePlugin;
import com.gtnewhorizons.gtnhgradle.GTNHModule;
import com.gtnewhorizons.gtnhgradle.PropertiesConfiguration;
import com.gtnewhorizons.gtnhgradle.fullpack.FullPackExtension;
import com.gtnewhorizons.gtnhgradle.fullpack.PrepareFullPackClientTask;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute;
import com.gtnewhorizons.retrofuturagradle.mcp.MCPTasks;
import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask;
import com.gtnewhorizons.retrofuturagradle.util.Distribution;

/** Adds tasks which run the locally built mod inside a complete GTNH installation. */
public class FullPackModule implements GTNHModule {

    public static final String DEFAULT_MANIFEST_URL = "https://github.com/Pxx500/DreamAssemblerXXL/releases/download/fullpack-daily/daily.json";
    public static final String DEFAULT_SERVER_MANIFEST_URL = "https://github.com/Pxx500/DreamAssemblerXXL/releases/download/fullpack-daily/daily-server.json";

    @Override
    public boolean isEnabled(@NotNull PropertiesConfiguration configuration) {
        return true;
    }

    @Override
    public void apply(GTNHGradlePlugin.@NotNull GTNHExtension gtnh, @NotNull Project project) throws Throwable {
        final FullPackExtension extension = project.getExtensions()
            .create("fullPack", FullPackExtension.class);
        extension.getManifestUrl()
            .convention(DEFAULT_MANIFEST_URL);
        extension.getServerManifestUrl()
            .convention(DEFAULT_SERVER_MANIFEST_URL);
        extension.getOwner()
            .convention(checkoutName(project.getProjectDir(), project.getName()));
        extension.getGitHubToken()
            .convention(
                project.getProviders()
                    .environmentVariable("GITHUB_TOKEN"));
        extension.getPreferMavenLocal()
            .convention(false);
        extension.getCleanServerRuntime()
            .convention(false);
        extension.getCacheDirectory()
            .convention(
                project.getLayout()
                    .dir(
                        project.provider(
                            () -> new File(
                                project.getGradle()
                                    .getGradleUserHomeDir(),
                                "caches/gtnh/fullpack"))));

        final Configuration productionMods = project.getConfigurations()
            .create("fullPackProductionMods", configuration -> {
                configuration.setDescription("Production SRG variants considered for full-pack dependency overlays");
                configuration.setCanBeConsumed(false);
                configuration.setCanBeResolved(true);
                configuration.setVisible(false);
                configuration.getAttributes()
                    .attribute(
                        Usage.USAGE_ATTRIBUTE,
                        project.getObjects()
                            .named(Usage.class, Usage.JAVA_RUNTIME));
                configuration.getAttributes()
                    .attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        project.getObjects()
                            .named(Category.class, Category.LIBRARY));
                configuration.getAttributes()
                    .attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        project.getObjects()
                            .named(LibraryElements.class, LibraryElements.JAR));
                configuration.getAttributes()
                    .attribute(
                        ObfuscationAttribute.OBFUSCATION_ATTRIBUTE,
                        ObfuscationAttribute.getSrg(project.getObjects()));
            });
        final Set<String> requestedProductionModules = new LinkedHashSet<>();
        for (String sourceName : List.of(
            "api",
            "implementation",
            "compileOnly",
            "compileOnlyApi",
            "runtimeOnly",
            "runtimeOnlyNonPublishable",
            "devOnlyNonPublishable")) {
            final Configuration source = project.getConfigurations()
                .findByName(sourceName);
            if (source != null) {
                source.getDependencies()
                    .all(
                        dependency -> mirrorProductionDependency(
                            dependency,
                            productionMods,
                            requestedProductionModules));
            }
        }
        final var productionArtifacts = productionMods.getIncoming()
            .artifactView(view -> view.lenient(true));
        final Provider<Map<String, String>> resolvedProductionArtifacts = project.provider(
            () -> describeResolvedArtifacts(
                productionArtifacts.getArtifacts()
                    .getArtifacts()));

        final TaskContainer tasks = project.getTasks();
        final File clientRuntimePathFile = project.getLayout()
            .getBuildDirectory()
            .file("fullpack/client-runtime.path")
            .get()
            .getAsFile();
        final File serverRuntimePathFile = project.getLayout()
            .getBuildDirectory()
            .file("fullpack/server-runtime.path")
            .get()
            .getAsFile();
        final File launcherPatch = project.getLayout()
            .getBuildDirectory()
            .file("fullpack/lwjgl3ify-forgePatches.jar")
            .get()
            .getAsFile();
        final TaskProvider<ReobfuscatedJar> reobfJar = tasks.named("reobfJar", ReobfuscatedJar.class);
        final Action<PrepareFullPackClientTask> configurePrepare = task -> {
            task.setGroup("GTNH Buildscript");
            task.getOwner()
                .set(extension.getOwner());
            task.getGitHubToken()
                .set(extension.getGitHubToken());
            task.getPreferMavenLocal()
                .set(extension.getPreferMavenLocal());
            task.getCleanRuntime()
                .convention(false);
            task.getCacheDirectory()
                .set(extension.getCacheDirectory());
            task.getMavenLocalRepository()
                .set(new File(System.getProperty("user.home"), ".m2/repository"));
            task.getProductionOverlayFiles()
                .from(productionArtifacts.getFiles());
            task.getProductionOverlayArtifacts()
                .set(resolvedProductionArtifacts);
            task.getRequestedProductionModules()
                .set(project.provider(() -> List.copyOf(requestedProductionModules)));
            task.getLocalModJar()
                .set(reobfJar.flatMap(ReobfuscatedJar::getArchiveFile));
            task.getOutputs()
                .upToDateWhen(ignored -> false);
        };
        final TaskProvider<PrepareFullPackClientTask> prepare = tasks
            .register("prepareFullPackClient", PrepareFullPackClientTask.class, task -> {
                configurePrepare.execute(task);
                task.setDescription("Downloads and assembles a complete GTNH client with the locally built mod");
                task.getManifestUrl()
                    .set(extension.getManifestUrl());
                task.getRuntimeDirectoryName()
                    .set("client");
                task.getRuntimePathFile()
                    .fileValue(clientRuntimePathFile);
                task.getLauncherPatchPath()
                    .set(".gtnh/launcher/lwjgl3ify-forgePatches.jar");
                task.getLauncherPatchFile()
                    .fileValue(launcherPatch);
            });
        final TaskProvider<PrepareFullPackClientTask> prepareServer = tasks
            .register("prepareFullPackServer", PrepareFullPackClientTask.class, task -> {
                configurePrepare.execute(task);
                task.setDescription("Downloads and assembles a complete GTNH server with the locally built mod");
                task.getManifestUrl()
                    .set(extension.getServerManifestUrl());
                task.getRuntimeDirectoryName()
                    .set("server");
                task.getCleanRuntime()
                    .set(extension.getCleanServerRuntime());
                task.getRuntimePathFile()
                    .fileValue(serverRuntimePathFile);
            });

        final MinecraftExtension minecraft = project.getExtensions()
            .getByType(MinecraftExtension.class);
        final MinecraftTasks minecraftTasks = project.getExtensions()
            .getByType(MinecraftTasks.class);
        final MCPTasks mcpTasks = project.getExtensions()
            .getByType(MCPTasks.class);
        final Action<RunMinecraftTask> configureRun = task -> {
            task.setup(project);
            task.getMcExtExtraRunJvmArguments()
                .set(
                    minecraft.getExtraRunJvmArguments()
                        .map(
                            arguments -> arguments.stream()
                                .filter(argument -> !argument.equals("-Dmixin.debug.countInjections=true"))
                                .toList()));
            task.setGroup("Modded Minecraft");
            task.getJavaLauncher()
                .set(
                    gtnh.getToolchainService()
                        .launcherFor(
                            toolchain -> toolchain.getLanguageVersion()
                                .set(JavaLanguageVersion.of(17))));
            @SuppressWarnings("unchecked")
            final List<String> modernJvmArgs = (List<String>) project.property("modernJvmArgs");
            task.getExtraJvmArgs()
                .addAll(modernJvmArgs);
        };

        tasks.register("runFullPack", RunMinecraftTask.class, Distribution.CLIENT)
            .configure(task -> {
                task.getLwjglVersion()
                    .set(3);
                configureRun.execute(task);
                task.setDescription("Runs the complete GTNH client with the locally built mod");
                task.dependsOn(
                    minecraftTasks.getTaskDownloadVanillaJars(),
                    minecraftTasks.getTaskDownloadVanillaAssets(),
                    prepare);

                task.classpath(mcpTasks.getForgeUniversalConfiguration());
                task.classpath(minecraftTasks.getVanillaClientLocation());
                task.classpath(mcpTasks.getPatchedConfiguration());
                task.setClasspath(
                    project.files(launcherPatch)
                        .plus(task.getClasspath()));
                task.getMainClass()
                    .set("com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread");
                task.getTweakClasses()
                    .add(
                        minecraft.getMinorMcVersion()
                            .map(
                                version -> version <= 7 ? "cpw.mods.fml.common.launcher.FMLTweaker"
                                    : "net.minecraftforge.fml.common.launcher.FMLTweaker"));
                task.doFirst("select prepared full-pack runtime", currentTask -> {
                    final RunMinecraftTask runTask = (RunMinecraftTask) currentTask;
                    final File preparedRuntime = readRuntimeDirectory(clientRuntimePathFile);
                    if (!launcherPatch.isFile()) {
                        throw new GradleException("Prepared full-pack runtime is missing " + launcherPatch);
                    }
                    runTask.setWorkingDir(preparedRuntime);
                });
            });
        tasks.register("runFullPackServer", RunMinecraftTask.class, Distribution.DEDICATED_SERVER)
            .configure(task -> {
                configureRun.execute(task);
                task.setDescription("Runs the complete GTNH server with the locally built mod");
                task.dependsOn(prepareServer);
                task.setClasspath(project.files());
                task.getMainClass()
                    .set("-jar");
                task.getExtraArgs()
                    .set(List.of());
                task.getArgumentProviders()
                    .add(
                        () -> List.of(
                            new File(readRuntimeDirectory(serverRuntimePathFile), "lwjgl3ify-forgePatches.jar")
                                .getAbsolutePath(),
                            "nogui"));
                task.doFirst("select prepared full-pack server runtime", currentTask -> {
                    final RunMinecraftTask runTask = (RunMinecraftTask) currentTask;
                    final File preparedRuntime = readRuntimeDirectory(serverRuntimePathFile);
                    final File serverLauncher = new File(preparedRuntime, "lwjgl3ify-forgePatches.jar");
                    if (!serverLauncher.isFile()) {
                        throw new GradleException("Prepared full-pack runtime is missing " + serverLauncher);
                    }
                    runTask.setWorkingDir(preparedRuntime);
                });
            });
    }

    private static File readRuntimeDirectory(File runtimePathFile) {
        try {
            return new File(
                Files.readString(runtimePathFile.toPath(), StandardCharsets.UTF_8)
                    .trim());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to locate the prepared full-pack runtime", e);
        }
    }

    private static String checkoutName(File projectDirectory, String projectName) {
        final Path gitFile = projectDirectory.toPath()
            .resolve(".git");
        if (!Files.isRegularFile(gitFile)) {
            return projectName;
        }
        try {
            final String gitDirValue = Files.readString(gitFile, StandardCharsets.UTF_8)
                .trim();
            if (!gitDirValue.startsWith("gitdir: ")) {
                return projectName;
            }
            Path gitDirectory = Path.of(gitDirValue.substring("gitdir: ".length()));
            if (!gitDirectory.isAbsolute()) {
                gitDirectory = projectDirectory.toPath()
                    .resolve(gitDirectory);
            }
            final Path commonDirFile = gitDirectory.resolve("commondir");
            if (!Files.isRegularFile(commonDirFile)) {
                return projectName;
            }
            final Path commonDirectory = gitDirectory.resolve(
                Files.readString(commonDirFile, StandardCharsets.UTF_8)
                    .trim())
                .normalize();
            return commonDirectory.getParent()
                .getFileName()
                .toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to identify the main Git checkout", e);
        }
    }

    private static void mirrorProductionDependency(Dependency dependency, Configuration destination,
        Set<String> requestedModules) {
        if (!(dependency instanceof ExternalModuleDependency module) || module.getGroup() == null
            || module.getVersion() == null
            || !hasSupportedClassifier(module)) {
            return;
        }
        final String coordinates = module.getGroup() + ":" + module.getName() + ":" + module.getVersion();
        if (!requestedModules.add(coordinates)) {
            return;
        }
        final ExternalModuleDependency production = module.copy();
        production.setTransitive(false);
        production.getArtifacts()
            .clear();
        destination.getDependencies()
            .add(production);
    }

    private static boolean hasSupportedClassifier(ExternalModuleDependency dependency) {
        for (DependencyArtifact artifact : dependency.getArtifacts()) {
            final String classifier = artifact.getClassifier();
            if (classifier != null && !classifier.isBlank() && !classifier.equals("dev") && !classifier.equals("api")) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> describeResolvedArtifacts(Set<ResolvedArtifactResult> artifacts) {
        final Map<String, String> resolved = new LinkedHashMap<>();
        for (ResolvedArtifactResult artifact : artifacts) {
            if (!(artifact.getId()
                .getComponentIdentifier() instanceof ModuleComponentIdentifier module)) {
                continue;
            }
            final String coordinates = module.getGroup() + ":" + module.getModule() + ":" + module.getVersion();
            final String previous = resolved.put(
                coordinates,
                artifact.getFile()
                    .getAbsolutePath());
            if (previous != null) {
                throw new IllegalStateException("Multiple production artifacts resolved for " + coordinates);
            }
        }
        return Map.copyOf(resolved);
    }
}
