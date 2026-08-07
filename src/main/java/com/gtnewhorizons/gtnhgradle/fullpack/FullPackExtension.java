package com.gtnewhorizons.gtnhgradle.fullpack;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/** Configuration for assembling and running a complete GTNH installation. */
public abstract class FullPackExtension {

    public abstract Property<String> getManifestUrl();

    public abstract Property<String> getServerManifestUrl();

    public abstract Property<String> getOwner();

    public abstract Property<String> getGitHubToken();

    public abstract DirectoryProperty getCacheDirectory();

    public abstract Property<Boolean> getPreferMavenLocal();

    public abstract Property<Boolean> getCleanServerRuntime();
}
