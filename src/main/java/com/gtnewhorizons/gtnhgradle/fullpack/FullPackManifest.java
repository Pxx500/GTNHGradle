package com.gtnewhorizons.gtnhgradle.fullpack;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

/** An installation plan for a complete GTNH client. */
public record FullPackManifest(String digest, List<FullPackManifest.File> files,
    List<FullPackManifest.Archive> archives, Map<String, String> textFiles) {

    public sealed interface Asset permits File, Archive {

        URI url();

        Authentication authentication();
    }

    /** One file copied directly into the runtime. */
    public record File(String owner, String path, URI url, MavenModule maven, Authentication authentication)
        implements Asset {}

    /** One ZIP archive extracted into the runtime root. */
    public record Archive(URI url, List<String> exclude, boolean keepExisting, Authentication authentication)
        implements Asset {}

    /** Maven coordinates of a mod in the pack. */
    public record MavenModule(String group, String name, String version) {}

    public enum Authentication {
        @SerializedName("none")
        NONE,
        @SerializedName("github")
        GITHUB
    }
}
