package io.github.ralfspoeth.xldr.app;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The marker convention of a feed: which files in {@code in/} signal that a data
 * file has arrived complete, and how the data file is named from the marker.
 * <p>
 * The pattern uses the same {@code glob:} / {@code regex:} prefixes as
 * {@link FileSystems#getDefault()}'s {@link java.nio.file.FileSystem#getPathMatcher
 * getPathMatcher} and is matched against the file <em>name</em> only.
 * <ul>
 *   <li>{@code glob:*.{ok,ready,done}} - matches {@code report.csv.done}; the
 *       data file is the marker name with its last dotted suffix removed,
 *       {@code report.csv}. (Glob alternation is comma-separated, per Java's
 *       {@code PathMatcher}.)</li>
 *   <li>{@code regex:(x.*\.xml)\.done} - matches {@code x123.xml.done}; the data
 *       file is capturing group 1, {@code x123.xml}. A regex with no capturing
 *       group falls back to the suffix rule, like a glob.</li>
 * </ul>
 */
public final class Sentinel {

    private static final String GLOB = "glob:";
    private static final String REGEX = "regex:";

    private final String spec;
    private final PathMatcher matcher;
    /** non-null only for a regex that declares at least one capturing group */
    private final Pattern captureGroup;

    private Sentinel(String spec, PathMatcher matcher, Pattern captureGroup) {
        this.spec = spec;
        this.matcher = matcher;
        this.captureGroup = captureGroup;
    }

    /**
     * @throws IllegalArgumentException if the pattern has no {@code glob:} or
     *                                  {@code regex:} prefix, or does not compile
     */
    public static Sentinel parse(String spec) {
        Objects.requireNonNull(spec, "sentinel");
        try {
            if (spec.startsWith(REGEX)) {
                var pattern = Pattern.compile(spec.substring(REGEX.length()));
                var capture = pattern.matcher("").groupCount() >= 1 ? pattern : null;
                return new Sentinel(spec, FileSystems.getDefault().getPathMatcher(spec), capture);
            } else if (spec.startsWith(GLOB)) {
                return new Sentinel(spec, FileSystems.getDefault().getPathMatcher(spec), null);
            } else {
                throw new IllegalArgumentException(
                        "sentinel must start with '" + GLOB + "' or '" + REGEX + "', was: " + spec);
            }
        } catch (PatternSyntaxException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("invalid sentinel pattern: " + spec, e);
        }
    }

    /**
     * Whether {@code file} is a marker.
     */
    public boolean isMarker(Path file) {
        return matcher.matches(file.getFileName());
    }

    /**
     * The data file the marker names, or empty if it cannot be derived - a glob
     * marker without a dotted suffix, or a captured group that did not match.
     */
    public Optional<Path> dataFileOf(Path marker) {
        var name = marker.getFileName().toString();
        if (captureGroup != null) {
            var m = captureGroup.matcher(name);
            return m.matches() && m.group(1) != null
                    ? Optional.of(marker.resolveSibling(m.group(1)))
                    : Optional.empty();
        }
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
