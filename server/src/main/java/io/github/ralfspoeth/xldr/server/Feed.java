package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.xldr.spec.MappingSpec;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * An active feed: a directory below one of the configured roots that holds a
 * mapping spec, together with the four working directories.
 *
 * <pre>
 * &lt;root&gt;/&lt;feed&gt;/
 *     spec.json           promotes the directory to a feed
 *     env.properties      optional; what this deployment supplies to it
 *     in/                 producers move input files in here
 *     work/               claimed, currently being loaded
 *     archive/            loaded successfully, date partitioned
 *     hospital/           failed, together with an error log
 * </pre>
 *
 * @param specModified  stamp of the spec file when it was parsed; lets the
 *                      registry re-read it only when it actually changed
 * @param acceptMatcher which files in {@code in/} this feed claims, or
 *                      {@code null} to claim every file
 */
record Feed(
        Path directory,
        Path specFile,
        FileTime specModified,
        MappingSpec mappingSpec,
        @Nullable Sentinel sentinel,
        @Nullable PathMatcher acceptMatcher
) {

    /**
     * Whether this feed claims {@code file}, matched against its name only. A
     * feed with no accept pattern claims every file.
     */
    public boolean accepts(Path file) {
        return acceptMatcher == null || acceptMatcher.matches(file.getFileName());
    }

    /**
     * Created below a feed directory when the feed becomes active.
     */
    public static final List<String> SUBDIRECTORIES = List.of("in", "work", "archive", "hospital");

    public String name() {
        return directory.getFileName().toString();
    }

    public Path in() {
        return directory.resolve("in");
    }

    public Path work() {
        return directory.resolve("work");
    }

    public Path archive() {
        return directory.resolve("archive");
    }

    public Path hospital() {
        return directory.resolve("hospital");
    }
}
