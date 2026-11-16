package com.gtnewhorizons.gtnhgradle.runs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftInstanceTest {

    @TempDir
    Path project;

    @Test
    void concurrentRunsSeparateInputsAndClosedSnapshotsAreReused() throws IOException {
        Path game = project.resolve("run/client");
        Path jar = project.resolve("mod.jar");
        Files.writeString(jar, "original");
        Path instances = project.resolve("instances");
        Path firstDirectory;
        try (
            var first = MinecraftInstance
                .prepare(instances, ":runClient", game, List.of(jar), project.resolve("cache"), true);
            var second = MinecraftInstance
                .prepare(instances, ":runClient", game, List.of(jar), project.resolve("cache"), true)) {
            firstDirectory = first.gameDirectory();
            assertNotEquals(firstDirectory, second.gameDirectory());
            assertTrue(Files.isSameFile(firstDirectory.resolve("saves"), game.resolve("saves")));
            Files.writeString(jar, "rebuilt");
            assertEquals(
                "original",
                Files.readString(
                    first.classpath()
                        .get(0)));
            assertEquals(
                "original",
                Files.readString(
                    second.classpath()
                        .get(0)));
        }
        Files.writeString(jar, "original");
        try (var reused = MinecraftInstance
            .prepare(instances, ":runClient", game, List.of(jar), project.resolve("cache"), true)) {
            assertEquals(firstDirectory, reused.gameDirectory());
        }
    }

    @Test
    void cleanupRemovesExpiredSnapshotsWithoutFollowingSaveLinks() throws IOException {
        Path game = project.resolve("run/client");
        Path jar = project.resolve("mod.jar");
        Files.writeString(jar, "original");
        Path instances = project.resolve("instances");
        Path old;
        try (var first = MinecraftInstance
            .prepare(instances, ":runClient", game, List.of(jar), project.resolve("cache"), true)) {
            old = first.gameDirectory()
                .getParent();
            Files.createDirectories(
                first.gameDirectory()
                    .resolve("saves/world"));
            Files.writeString(
                first.gameDirectory()
                    .resolve("saves/world/level.dat"),
                "user world");
        }
        Files.setLastModifiedTime(
            old,
            FileTime.from(
                Instant.now()
                    .minus(Duration.ofHours(48))));
        Files.writeString(jar, "rebuilt");
        try (var current = MinecraftInstance
            .prepare(instances, ":runClient", game, List.of(jar), project.resolve("cache"), true)) {
            assertFalse(Files.exists(old));
            assertEquals(
                "user world",
                Files.readString(
                    current.gameDirectory()
                        .resolve("saves/world/level.dat")));
            assertEquals("user world", Files.readString(game.resolve("saves/world/level.dat")));
        }
    }

    @Test
    void consecutiveServersStartFreshWithoutChangingSourceWorldOrConfiguration() throws IOException {
        Path game = project.resolve("run/server");
        Path world = Path.of("worlds/probe");
        Files.createDirectories(game.resolve(world));
        Files.writeString(
            game.resolve(world)
                .resolve("level.dat"),
            "source world");
        String properties = "level-name=worlds/probe\nmax-players=2\n";
        Files.writeString(game.resolve("server.properties"), properties);
        Files.writeString(game.resolve("eula.txt"), "eula=false\n");
        Path instances = project.resolve("instances");
        Path firstGame;
        try (var first = MinecraftInstance
            .prepare(instances, ":runServer25", game, List.of(), project.resolve("cache"), false)) {
            firstGame = first.gameDirectory();
            assertFalse(Files.exists(firstGame.resolve(world)), "The source world must not be inherited");
            assertEquals(properties, Files.readString(firstGame.resolve("server.properties")));
            assertEquals("eula=false\n", Files.readString(firstGame.resolve("eula.txt")));
            Files.createDirectories(firstGame.resolve(world));
            Files.writeString(
                firstGame.resolve(world)
                    .resolve("level.dat"),
                "first run world");
            Files.writeString(firstGame.resolve("server.properties"), "level-name=another-world\n");
            Files.writeString(firstGame.resolve("eula.txt"), "eula=true\n");
        }
        assertEquals(properties, Files.readString(game.resolve("server.properties")));
        assertEquals("eula=false\n", Files.readString(game.resolve("eula.txt")));
        try (var second = MinecraftInstance
            .prepare(instances, ":runServer25", game, List.of(), project.resolve("cache"), false)) {
            assertNotEquals(firstGame, second.gameDirectory());
            assertFalse(
                Files.exists(
                    second.gameDirectory()
                        .resolve(world)),
                "The previous run's world must not be inherited");
            assertEquals(
                properties,
                Files.readString(
                    second.gameDirectory()
                        .resolve("server.properties")));
            assertEquals(
                "eula=false\n",
                Files.readString(
                    second.gameDirectory()
                        .resolve("eula.txt")));
        }
        assertEquals(
            "first run world",
            Files.readString(
                firstGame.resolve(world)
                    .resolve("level.dat")));
        assertEquals(
            "source world",
            Files.readString(
                game.resolve(world)
                    .resolve("level.dat")));
        assertEquals(properties, Files.readString(game.resolve("server.properties")));
        assertEquals("eula=false\n", Files.readString(game.resolve("eula.txt")));
    }

    @Test
    void changedGameInputsAreAppliedWithoutRebuildingTheMod() throws IOException {
        Path game = project.resolve("run/client");
        Path config = game.resolve("config/probe.cfg");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "before");
        Files.writeString(game.resolve("options.txt"), "fov:0.0\n");
        Path jar = project.resolve("mod.jar");
        Files.writeString(jar, "unchanged mod");
        Path instances = project.resolve("instances");
        Path cache = project.resolve("cache");
        try (var first = MinecraftInstance.prepare(instances, ":runClient", game, List.of(jar), cache, true)) {
            assertEquals(
                "before",
                Files.readString(
                    first.gameDirectory()
                        .resolve("config/probe.cfg")));
        }
        Files.writeString(config, "after");
        Files.writeString(game.resolve("options.txt"), "fov:1.0\n");
        try (var second = MinecraftInstance.prepare(instances, ":runClient", game, List.of(jar), cache, true)) {
            assertEquals(
                "after",
                Files.readString(
                    second.gameDirectory()
                        .resolve("config/probe.cfg")));
            assertEquals(
                "fov:1.0\n",
                Files.readString(
                    second.gameDirectory()
                        .resolve("options.txt")));
        }
    }
}
