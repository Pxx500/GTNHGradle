package com.gtnewhorizons.gtnhgradle.fullpack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/** Holds shared runtime leases until the Gradle build and its Minecraft process finish. */
public abstract class FullPackRuntimeLeaseService implements BuildService<BuildServiceParameters.None>, AutoCloseable {

    private static final long LOCK_START = 0L;

    private final Map<Path, FileChannel> channels = new LinkedHashMap<>();

    public synchronized void acquire(Path runtime) {
        if (channels.containsKey(runtime)) {
            return;
        }
        final Path leaseFile = leasePath(runtime);
        try {
            Files.createDirectories(leaseFile.getParent());
            final FileChannel channel = FileChannel
                .open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                channel.lock(LOCK_START, Long.MAX_VALUE, true);
                channels.put(runtime, channel);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to lease full-pack runtime " + runtime, e);
        }
    }

    public static Path leasePath(Path runtime) {
        return runtime.resolveSibling(".active-" + runtime.getFileName() + ".lock");
    }

    @Override
    public synchronized void close() {
        try {
            for (Map.Entry<Path, FileChannel> entry : channels.entrySet()) {
                try (FileChannel channel = entry.getValue()) {
                    FullPackInstaller.recordLastUsed(entry.getKey());
                }
            }
            channels.clear();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to release full-pack runtime lease", e);
        }
    }
}
