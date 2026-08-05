package com.gtnewhorizons.gtnhgradle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gtnewhorizons.retrofuturagradle.shadow.com.google.common.collect.ImmutableMap;

class FullPackModuleFunctionalTest {

    @TempDir
    Path projectDirectory;

    @Test
    void conventionPluginRegistersFullPackTasksWithoutProjectConfiguration() throws IOException {
        setupProject();

        BuildResult result = createRunner("tasks", "--all").build();

        assertTrue(
            result.getOutput()
                .contains("prepareFullPackClient"));
        assertTrue(
            result.getOutput()
                .contains("runFullPack"));
    }

    @Test
    void runFullPackBuildsAndPreparesTheLocalProductionJar() throws IOException {
        setupProject();

        BuildResult result = createRunner("runFullPack", "--dry-run", "--configuration-cache").build();

        assertTrue(
            result.getOutput()
                .contains(":reobfJar SKIPPED"));
        assertTrue(
            result.getOutput()
                .contains(":prepareFullPackClient SKIPPED"));
        assertTrue(
            result.getOutput()
                .contains(":runFullPack SKIPPED"));
    }

    @Test
    void runFullPackUsesProductionLauncherWithoutDevelopmentMixinValidation() throws IOException {
        setupProject();
        Files.writeString(projectDirectory.resolve("gradle.properties"), """
            usesMixins = true
            usesMixinDebug = true
            mixinsPackage = mixin.mixins
            """, StandardOpenOption.APPEND);
        Files.createDirectories(projectDirectory.resolve("src/main/java/com/myname/mymodid/mixin/mixins"));
        Path launcherPatch = projectDirectory.resolve("build/fullpack/lwjgl3ify-forgePatches.jar");
        Files.createDirectories(launcherPatch.getParent());
        Files.writeString(launcherPatch, "launcher");
        Path runtimePathFile = projectDirectory.resolve("build/fullpack/client-runtime.path");
        Files.createDirectories(runtimePathFile.getParent());
        Files.writeString(
            runtimePathFile,
            projectDirectory.resolve("fake-runtime")
                .toString());
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), """

            tasks.register("verifyRunFullPackLauncher") {
                doLast {
                    val run = tasks.named<com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask>(
                        "runFullPack"
                    ).get()
                    check(run.lwjglVersion.get() == 3) { "runFullPack must use LWJGL 3" }
                    check(run.javaLauncher.get().metadata.languageVersion.asInt() == 17) {
                        "runFullPack must use Java 17"
                    }
                    check(
                        run.mainClass.get() ==
                            "com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread"
                    ) { "runFullPack must enter through RetroFuturaBootstrap" }
                    check(
                        run.extraJvmArgs.get().contains(
                            "-Djava.system.class.loader=" +
                                "com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader"
                        )
                    ) { "runFullPack must install the RFB system class loader" }
                    check(
                        !run.calculateJvmArgs().contains("-Dmixin.debug.countInjections=true")
                    ) { "runFullPack must not enable development-only Mixin injection validation" }
                    check(
                        run.classpath.files.first().canonicalFile ==
                            file("build/fullpack/lwjgl3ify-forgePatches.jar").canonicalFile
                    ) { "lwjgl3ify forgePatches must be first on the launch classpath" }
                }
            }
            """, StandardOpenOption.APPEND);

        BuildResult result = createRunner("verifyRunFullPackLauncher").build();

        assertTrue(
            result.getOutput()
                .contains("BUILD SUCCESSFUL"));
    }

    @Test
    void devDependencyIsMirroredAsAnSrgProductionArtifactWithoutAClassifier() throws IOException {
        setupProject();
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), """

            dependencies {
                api("com.github.GTNewHorizons:ModularUI2:2.3.85-1.7.10:dev")
            }

            tasks.register("verifyFullPackProductionDependencies") {
                doLast {
                    val production = configurations.getByName("fullPackProductionMods")
                    val dependency = production.dependencies.single {
                        it.group == "com.github.GTNewHorizons" && it.name == "ModularUI2"
                    } as ExternalModuleDependency
                    check(dependency.artifacts.isEmpty())
                    check(
                        production.attributes.getAttribute(
                            com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute.OBFUSCATION_ATTRIBUTE
                        )?.name == "srg"
                    )
                }
            }
            """, StandardOpenOption.APPEND);

        BuildResult result = createRunner("verifyFullPackProductionDependencies").build();

        assertTrue(
            result.getOutput()
                .contains("BUILD SUCCESSFUL"));
    }

    private void setupProject() throws IOException {
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
            pluginManagement {
                repositories {
                    maven {
                        name = "GTNH Maven"
                        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
                    }
                    gradlePluginPortal()
                    mavenCentral()
                    mavenLocal()
                }
            }
            plugins {
                id("com.gtnewhorizons.gtnhsettingsconvention")
            }
            """);
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), """
            plugins {
                id("com.gtnewhorizons.gtnhconvention")
            }
            """);
        Files.writeString(projectDirectory.resolve("gradle.properties"), """
            modName = MyMod
            modId = mymodid
            modGroup = com.myname.mymodid
            enableModernJavaSyntax = true
            enableGenericInjection = true
            """);
        Files.createDirectories(projectDirectory.resolve("src/main/java/com/myname/mymodid"));
    }

    private GradleRunner createRunner(String... arguments) {
        return GradleRunner.create()
            .withEnvironment(ImmutableMap.of("VERSION", "1.0.0"))
            .withArguments(arguments)
            .forwardOutput()
            .withPluginClasspath()
            .withProjectDir(projectDirectory.toFile());
    }
}
