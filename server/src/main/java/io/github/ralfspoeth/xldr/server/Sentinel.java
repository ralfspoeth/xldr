package io.github.ralfspoeth.xldr.server;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Objects;
import java.util.Optional;

/**
 * The marker convention of a feed: which files in {@code in/} signal that a data
 * file has arrived complete, and how the data file is named from the marker.
 * <p>
 * The pattern is handed straight to {@link java.nio.file.FileSystem#getPathMatcher
 * getPathMatcher}, so it carries its own {@code glob:} or {@code regex:} prefix
 * and is matched against the file <em>name</em> only - for example
 * {@code glob:*.{ok,ready,done}} or {@code regex:.*\.done}. The data file is
 * always the marker name with its last dotted suffix removed, so
 * {@code report.csv.done} loads {@code report.csv}.
 */
public final class Sentinel {

    private final String spec;
    private final PathMatcher matcher;

    private Sentinel(String spec, PathMatcher matcher) {
        this.spec = spec;
        this.matcher = matcher;
    }

    /**
     * @param spec the marker pattern, in {@code glob:} or {@code regex:} form
     * @return the marker convention that pattern describes
     * @throws IllegalArgumentException if the pattern lacks a {@code glob:} or
     *                                  {@code regex:} prefix, or does not compile
     */
    public static Sentinel parse(String spec) {
        Objects.requireNonNull(spec, "sentinel");
        try {
            return new Sentinel(spec, FileSystems.getDefault().getPathMatcher(spec));
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("invalid sentinel pattern: " + spec, e);
        }
    }

    /**
     * @param file a file that has arrived in {@code in/}
     * @return whether it is a marker rather than a data file
     */
    public boolean isMarker(Path file) {
        return matcher.matches(file.getFileName());
    }

    /**
     * The data file the marker names - its own name with the last dotted suffix
     * removed.
     *
     * @param marker a file for which {@link #isMarker} holds
     * @return the data file it names, or empty if the name has no suffix to strip
     */
    public Optional<Path> dataFileOf(Path marker) {
        var name = marker.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot > 0
                ? Optional.of(marker.resolveSibling(name.substring(0, dot)))
                : Optional.empty();
    }

    @Override
    public String toString() {
        return spec;
    }
}
