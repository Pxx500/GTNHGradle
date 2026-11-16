package com.gtnewhorizons.gtnhgradle.runs;

import java.io.UncheckedIOException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.Project;

import com.gtnewhorizons.gtnhgradle.fullpack.FullPackRuntimeLeaseService;
import com.gtnewhorizons.retrofuturagradle.util.Distribution;
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask;

/** Adds input isolation to existing standard Minecraft tasks without selecting their runtime. */
public final class MinecraftRuns {

    private MinecraftRuns() {}

    public static void install(Project project) {
        Object runDirectory = project.findProperty("runDir");
        File requestedDirectory = runDirectory == null ? null : project.file(runDirectory);
        File root = project.getRootProject()
            .getLayout()
            .getProjectDirectory()
            .dir(".gradle/gtnh/instances")
            .getAsFile();
        Path sharedCache = project.getGradle()
            .getGradleUserHomeDir()
            .toPath()
            .resolve("caches");
        var service = project.getGradle()
            .getSharedServices()
            .registerIfAbsent("fullPackRuntimeLease", FullPackRuntimeLeaseService.class, ignored -> {});
        project.getTasks()
            .withType(RunMinecraftTask.class)
            .configureEach(task -> {
                if (!task.getName()
                    .matches("run(Client|Server).*")) return;
                if (requestedDirectory != null) {
                    task.setWorkingDir(requestedDirectory);
                    task.doFirst(
                        "select caller game directory",
                        current -> ((RunMinecraftTask) current).setWorkingDir(requestedDirectory));
                    return;
                }
                var frozenClasspath = project.getObjects()
                    .fileCollection();
                task.usesService(service);
                task.doFirst("snapshot Minecraft inputs", current -> {
                    RunMinecraftTask run = (RunMinecraftTask) current;
                    Path sourceGame = run.getWorkingDir()
                        .toPath()
                        .toAbsolutePath()
                        .normalize();
                    List<Path> classpath = run.getClasspath()
                        .getFiles()
                        .stream()
                        .map(File::toPath)
                        .toList();
                    MinecraftInstance instance = service.get()
                        .prepare(
                            run.getPath(),
                            root.toPath(),
                            sourceGame,
                            classpath,
                            sharedCache,
                            run.getSide() == Distribution.CLIENT);
                    frozenClasspath.setFrom(instance.classpath());
                    if (run.getJavaLauncher()
                        .get()
                        .getMetadata()
                        .getLanguageVersion()
                        .asInt() >= 9) {
                        try {
                            run.jvmArgs("@" + instance.writeClasspathArguments());
                            frozenClasspath.setFrom(List.of());
                        } catch (IOException failure) {
                            throw new UncheckedIOException("Failed to write Minecraft classpath arguments", failure);
                        }
                    }
                    run.setClasspath(frozenClasspath);
                    run.setWorkingDir(instance.gameDirectory());
                    run.getLogger()
                        .lifecycle("Minecraft instance prepared at {}", instance.gameDirectory());
                });
            });
    }
}
