package com.gtnewhorizons.gtnhgradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StandardMinecraftRunsFunctionalTest {

    @TempDir
    Path project;

    @Test
    void unnumberedClientSnapshotsInputsWithoutChangingJavaOrArguments() throws IOException {
        prepareRuntime("runClient");
        runner("runClient", "--configuration-cache").build();
        List<String> launched = Files.readAllLines(project.resolve("launched.txt"));
        Path game = Path.of(launched.get(0));
        assertTrue(game.startsWith(project.resolve(".gradle/gtnh/instances")), game.toString());
        assertTrue(
            Path.of(launched.get(1))
                .startsWith(game.getParent()),
            launched.get(1));
        assertEquals("1.8", launched.get(2));
        assertEquals("configured-token", launched.get(3));
        assertTrue(
            launched.get(4)
                .contains("--probe-argument"),
            launched.get(4));
    }

    @ParameterizedTest
    @CsvSource({ "runServer,1.8", "runClient17,17", "runServer17,17", "runClient21,21", "runServer21,21",
        "runClient25,25", "runServer25,25", "runClientX,21", "runServerX,21" })
    void standardVariantsKeepTheirLauncherAndArguments(String task, String javaVersion) throws IOException {
        prepareRuntime(task);
        runner(task).build();
        List<String> launched = Files.readAllLines(project.resolve("launched.txt"));
        Path game = Path.of(launched.get(0));
        Path frozen = Path.of(launched.get(1));
        assertTrue(game.startsWith(project.resolve(".gradle/gtnh/instances")), game.toString());
        assertTrue(frozen.startsWith(game.getParent()), frozen.toString());
        assertEquals(javaVersion, launched.get(2));
        assertEquals("configured-token", launched.get(3));
        assertTrue(
            launched.get(4)
                .contains("--probe-argument"),
            launched.get(4));
        byte[] original = Files.readAllBytes(frozen);
        Files.writeString(project.resolve("build/libs/probe.jar"), "replacement build");
        assertArrayEquals(original, Files.readAllBytes(frozen));
    }

    @ParameterizedTest
    @ValueSource(ints = { 17, 25 })
    void classpathArgumentsSupportUnicodeAndSpaces(int javaVersion) throws IOException {
        project = Files.createDirectories(project.resolve("Żółć space"));
        prepareRuntime("runClient" + javaVersion);
        runner("runClient" + javaVersion).build();
        assertTrue(
            Files.readString(project.resolve("launched.txt"))
                .contains("Żółć space"));
    }

    @Test
    void configurationCacheReplayReusesAnIdleSnapshot() throws IOException {
        prepareRuntime("runClient25");
        runner("runClient25", "--configuration-cache").build();
        String first = Files.readString(project.resolve("launched.txt"));
        String output = runner("runClient25", "--configuration-cache").build()
            .getOutput();
        assertTrue(output.contains("Reusing configuration cache"), output);
        assertEquals(first, Files.readString(project.resolve("launched.txt")));
    }

    @Test
    void longClasspathPreservesRfbPluginDiscovery() throws IOException {
        prepareRuntime("runClient25");
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
                tasks.named<com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask>("runClient25") {
                    val padding = (0 until 220).map { file("padding/entry-$it-" + "x".repeat(120)) }
                    padding.forEach { it.mkdirs() }
                    classpath(files(padding))
                    classpath(project.property("java17PatchDependenciesCfg"))
                    classpath(project.extensions.getByType<com.gtnewhorizons.retrofuturagradle.mcp.MCPTasks>().patchedConfiguration)
                    systemProperty("java.system.class.loader", "com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader")
                    systemProperty("probe.rfb", "true")
                }
                """,
            StandardOpenOption.APPEND);
        runner("runClient25").build();
        assertTrue(
            Files.readAllLines(project.resolve("launched.txt"))
                .get(1)
                .length() > 32767,
            "The JVM must see the real classpath, not a manifest JAR");
        assertEquals(
            "com.gtnewhorizons.rfbplugins.compat.ModernJavaCompatibilityPlugin",
            Files.readString(project.resolve("launched.txt.rfbPlugin")));
    }

    @Test
    void parallelClientsKeepClassesAndResourcesAfterCleanAndRebuild() throws Exception {
        prepareRuntime("runClient25");
        Files.writeString(project.resolve("build.gradle.kts"), """
            tasks.named<JavaExec>("runClient25") {
                val name = providers.gradleProperty("probeName").orElse("launched").get()
                systemProperty("probe.output", file("$name.txt").absolutePath)
                systemProperty("probe.release", file("$name.release").absolutePath)
            }
            """, StandardOpenOption.APPEND);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> runner("runClient25", "-PprobeName=first").build());
            awaitLaunch(project.resolve("first.txt"), first);
            Future<?> second = workers.submit(() -> runner("runClient25", "-PprobeName=second").build());
            awaitLaunch(project.resolve("second.txt"), second);
            Path firstGame = Path.of(
                Files.readAllLines(project.resolve("first.txt"))
                    .get(0));
            Path secondGame = Path.of(
                Files.readAllLines(project.resolve("second.txt"))
                    .get(0));
            assertNotEquals(firstGame, secondGame, "Concurrent runs need separate game directories");
            assertTrue(!first.isDone() && !second.isDone(), "Both JVMs must remain active during rebuild");
            Files.writeString(project.resolve("probe-source/probe-value.txt"), "replacement resource");
            runner("clean", "rebuildProbe").build();
            Files.writeString(project.resolve("first.release"), "");
            Files.writeString(project.resolve("second.release"), "");
            first.get(90, TimeUnit.SECONDS);
            second.get(90, TimeUnit.SECONDS);
            assertEquals("original resource", Files.readString(project.resolve("first.txt.late")));
            assertEquals("original resource", Files.readString(project.resolve("second.txt.late")));
        } finally {
            Files.writeString(project.resolve("first.release"), "");
            Files.writeString(project.resolve("second.release"), "");
            workers.shutdown();
            assertTrue(workers.awaitTermination(120, TimeUnit.SECONDS), "Probe builds must stop after release");
        }
    }

    private void awaitLaunch(Path output, Future<?> build) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!Files.isRegularFile(output)) {
            if (build.isDone()) {
                build.get();
                throw new AssertionError("The build finished without launching its probe");
            }
            if (System.nanoTime() > deadline) throw new AssertionError("The probe did not start: " + output);
            Thread.sleep(50);
        }
    }

    private void prepareRuntime(String task) throws IOException {
        if (task.startsWith("runServer")) {
            Files.createDirectories(project.resolve("run/server"));
            Files.writeString(project.resolve("run/server/eula.txt"), "eula=true\n");
            Files.writeString(project.resolve("run/server/server.properties"), "online-mode=true\n");
        }
        Files.writeString(project.resolve("settings.gradle.kts"), """
            pluginManagement {
                repositories {
                    maven { url = uri("https://nexus.gtnewhorizons.com/repository/public/") }
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            plugins { id("com.gtnewhorizons.gtnhsettingsconvention") }
            rootProject.name = "ProbeMod"
            """);
        Files.writeString(project.resolve("gradle.properties"), """
            modName=ProbeMod
            modId=probemod
            modGroup=example.probe
            enableModernJavaSyntax=false
            gtnh.modules.updater=false
            """);
        String javaHomes = System.getenv("ORG_GRADLE_PROJECT_org.gradle.java.installations.paths");
        if (javaHomes != null) {
            Files.writeString(
                project.resolve("gradle.properties"),
                "org.gradle.java.installations.paths=" + javaHomes + "\n",
                StandardOpenOption.APPEND);
        }
        if (task.endsWith("X")) {
            Files.writeString(
                project.resolve("gradle.properties"),
                "forceToolchainVersion=21\n",
                StandardOpenOption.APPEND);
        }
        Files.createDirectories(project.resolve("src/main/java/example/probe"));
        Path source = project.resolve("probe-source/Probe.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
            source,
            """
                public class Probe {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Path output = java.nio.file.Paths.get(System.getProperty("probe.output"));
                        String text = java.nio.file.Paths.get("").toAbsolutePath() + "\\n"
                            + System.getProperty("java.class.path") + "\\n"
                            + System.getProperty("java.specification.version") + "\\n"
                            + System.getProperty("probe.token") + "\\n" + String.join(",", args);
                        java.nio.file.Files.write(output, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        if (Boolean.getBoolean("probe.rfb")) {
                            ClassLoader loader = ClassLoader.getSystemClassLoader();
                            java.net.URL[] urls = (java.net.URL[]) loader.getClass().getMethod("getURLs").invoke(loader);
                            for (java.net.URL url : urls) {
                                java.io.File file = new java.io.File(url.toURI());
                                if (!file.isFile() || !file.getName().endsWith(".jar")) continue;
                                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
                                    java.util.jar.JarEntry descriptor = jar.getJarEntry("META-INF/rfb-plugin/rfb-modern-java.properties");
                                    if (descriptor == null) continue;
                                    java.util.Properties properties = new java.util.Properties();
                                    try (java.io.InputStream input = jar.getInputStream(descriptor)) { properties.load(input); }
                                    String name = properties.getProperty("className");
                                    Object plugin = Class.forName(name, true, loader).getDeclaredConstructor().newInstance();
                                    java.nio.file.Files.write(java.nio.file.Paths.get(output + ".rfbPlugin"),
                                        plugin.getClass().getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                }
                            }
                        }
                        String release = System.getProperty("probe.release");
                        if (release != null) {
                            long deadline = System.currentTimeMillis() + 180000;
                            while (!java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(release))) {
                                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("Probe release timed out");
                                Thread.sleep(50);
                            }
                            Object value = Class.forName("LateProbe").getDeclaredMethod("value").invoke(null);
                            java.nio.file.Files.write(java.nio.file.Paths.get(output + ".late"),
                                value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
                class LateProbe {
                    public static String value() throws Exception {
                        try (java.io.InputStream input = LateProbe.class.getResourceAsStream("/probe-value.txt")) {
                            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[1024];
                            int read;
                            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                """);
        int compiled = ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-source",
                "8",
                "-target",
                "8",
                "-d",
                source.getParent()
                    .toString(),
                source.toString());
        assertEquals(0, compiled, "Probe compilation must succeed");
        Path jar = project.resolve("build/libs/probe.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(source.resolveSibling("probe-value.txt"), "original resource");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("Probe.class", "LateProbe.class", "probe-value.txt")) {
                output.putNextEntry(new JarEntry(name));
                Files.copy(source.resolveSibling(name), output);
                output.closeEntry();
            }
        }
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins { id("com.gtnewhorizons.gtnhconvention") }
            tasks.register<Jar>("rebuildProbe") {
                from("probe-source") { include("*.class", "probe-value.txt") }
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
                archiveFileName.set("probe.jar")
            }
            tasks.named<com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask>("%s") {
                setDependsOn(emptyList<Any>())
                setClasspath(files("build/libs/probe.jar"))
                mainClass.set("Probe")
                extraJvmArgs.set(emptyList())
                mcExtExtraRunJvmArguments.set(emptyList())
                minHeapSize = "16M"
                maxHeapSize = "64M"
                systemProperty("probe.output", file("launched.txt").absolutePath)
                systemProperty("probe.token", "configured-token")
                extraArgs.add("--probe-argument")
            }
            """.formatted(task));
    }

    private GradleRunner runner(String... arguments) {
        var environment = new HashMap<>(System.getenv());
        environment.put("VERSION", "1.0.0");
        return GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withEnvironment(environment)
            .withArguments(arguments);
    }
}
