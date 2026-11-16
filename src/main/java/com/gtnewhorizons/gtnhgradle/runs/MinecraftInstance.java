package com.gtnewhorizons.gtnhgradle.runs;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.gtnewhorizons.gtnhgradle.fullpack.FullPackAssetCache;
import com.gtnewhorizons.gtnhgradle.fullpack.FullPackInstaller;
import com.gtnewhorizons.gtnhgradle.fullpack.FullPackRuntimeLeaseService;

/** A leased game directory with immutable local classpath inputs and persistent client saves. */
public final class MinecraftInstance implements Closeable {

    private static final Set<String> TRANSIENT_FILES = Set.of("saves", "logs", "crash-reports", "session.lock");
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lease;
    private List<Path> classpath;

    private MinecraftInstance(Path directory, FileChannel channel, FileLock lease) {
        this.directory = directory;
        this.channel = channel;
        this.lease = lease;
    }

    public static MinecraftInstance prepare(Path root, String task, Path sourceGame, List<Path> classpath,
        Path sharedCache, boolean client) throws IOException {
        StringBuilder inputs = new StringBuilder(task).append('\n')
            .append(sourceGame);
        for (Path path : classpath) appendIdentity(inputs, path);
        Path world = client ? null : serverWorld(sourceGame);
        List<Path> gameInputs = gameInputs(root, sourceGame, world);
        for (Path path : gameInputs) appendIdentity(inputs, path);
        String identity = FullPackAssetCache.sha256(inputs.toString());
        Files.createDirectories(root);
        for (int concurrent = 0;; concurrent++) {
            Path selected = FullPackInstaller.selectRuntime(root, identity + "\n" + concurrent);
            FileChannel channel = FileChannel.open(
                FullPackRuntimeLeaseService.leasePath(selected),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
            FileLock lease = tryLock(channel);
            if (lease == null || (!client && Files.exists(selected.resolve(".prepared")))) {
                channel.close();
                continue;
            }
            MinecraftInstance instance = new MinecraftInstance(selected, channel, lease);
            try {
                Files.createDirectories(selected);
                instance.snapshot(sourceGame, classpath, sharedCache, client, gameInputs);
                cleanupExpired(root, selected);
                return instance;
            } catch (IOException | RuntimeException failure) {
                try (channel) {
                    lease.release();
                }
                throw failure;
            }
        }
    }

    private void snapshot(Path sourceGame, List<Path> originalClasspath, Path sharedCache, boolean client,
        List<Path> gameInputs) throws IOException {
        Path game = gameDirectory();
        Files.createDirectories(game);
        boolean prepared = Files.exists(directory.resolve(".prepared"));
        if (!prepared) for (Path entry : gameInputs) copy(entry, game.resolve(entry.getFileName()));
        if (client) {
            FullPackInstaller.connectDirectory(game.resolve("saves"), sourceGame.resolve("saves"));
        }
        List<Path> frozen = new ArrayList<>();
        for (int index = 0; index < originalClasspath.size(); index++) {
            Path source = originalClasspath.get(index)
                .toAbsolutePath()
                .normalize();
            if (source.startsWith(sourceGame)) {
                frozen.add(game.resolve(sourceGame.relativize(source)));
            } else if (source.startsWith(sharedCache)) {
                frozen.add(source);
            } else {
                Path destination = directory.resolve("classpath")
                    .resolve(Integer.toString(index))
                    .resolve(source.getFileName());
                if (!prepared) copy(source, destination);
                frozen.add(destination);
            }
        }
        classpath = List.copyOf(frozen);
        Files.writeString(directory.resolve(".prepared"), "");
    }

    private static List<Path> gameInputs(Path root, Path sourceGame, Path world) throws IOException {
        if (!Files.isDirectory(sourceGame)) return List.of();
        try (var entries = Files.list(sourceGame)) {
            return entries.filter(
                entry -> !TRANSIENT_FILES.contains(
                    entry.getFileName()
                        .toString()))
                .filter(entry -> !root.startsWith(entry))
                .filter(
                    entry -> world == null || !sourceGame.resolve(world)
                        .startsWith(entry))
                .sorted()
                .toList();
        }
    }

    private static Path serverWorld(Path sourceGame) throws IOException {
        Properties properties = new Properties();
        Path file = sourceGame.resolve("server.properties");
        if (Files.isRegularFile(file)) {
            try (var input = Files.newInputStream(file)) {
                properties.load(input);
            }
        }
        return Path.of(properties.getProperty("level-name", "world"));
    }

    private static void appendIdentity(StringBuilder identity, Path input) throws IOException {
        identity.append('\n')
            .append(
                input.toAbsolutePath()
                    .normalize());
        if (Files.isRegularFile(input)) {
            identity.append(':')
                .append(FullPackAssetCache.sha256(input));
        } else if (Files.isDirectory(input)) {
            try (var entries = Files.walk(input)) {
                for (Path path : entries.filter(Files::isRegularFile)
                    .sorted()
                    .toList()) {
                    identity.append('\n')
                        .append(input.relativize(path))
                        .append(':')
                        .append(FullPackAssetCache.sha256(path));
                }
            }
        }
    }

    private static void copy(Path source, Path destination) throws IOException {
        if (!Files.exists(source)) return;
        if (!Files.isDirectory(source)) {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public Path gameDirectory() {
        return directory.resolve("game");
    }

    public List<Path> classpath() {
        return classpath;
    }

    public Path writeClasspathArguments() throws IOException {
        String joined = String.join(
            File.pathSeparator,
            classpath.stream()
                .map(Path::toString)
                .toList());
        Path arguments = directory.resolve("classpath.args");
        Files.writeString(
            arguments,
            "-classpath\n\"" + joined.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"\n",
            Charset.forName(System.getProperty("native.encoding")));
        return arguments;
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException busy) {
            return null;
        }
    }

    private static void cleanupExpired(Path root, Path current) throws IOException {
        Instant expiry = Instant.now()
            .minus(Duration.ofHours(24));
        try (var entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory)
                .toList()) {
                if (directory.equals(current) || Files.getLastModifiedTime(directory)
                    .toInstant()
                    .isAfter(expiry)) continue;
                try (FileChannel channel = FileChannel.open(
                    FullPackRuntimeLeaseService.leasePath(directory),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE); FileLock lease = tryLock(channel)) {
                    if (lease == null) continue;
                    delete(directory);
                    Files.deleteIfExists(root.resolve(".identity-" + directory.getFileName()));
                }
            }
        }
    }

    private static void delete(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    Files.delete(path);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() throws IOException {
        try (channel) {
            try {
                Files.setLastModifiedTime(directory, FileTime.from(Instant.now()));
            } finally {
                lease.release();
            }
        }
    }
}
