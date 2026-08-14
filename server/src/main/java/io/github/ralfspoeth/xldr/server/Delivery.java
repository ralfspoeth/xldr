package io.github.ralfspoeth.xldr.server;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.PatternSyntaxException;

/**
 * How files reach a feed: what a producer delivers, and how the server knows a
 * delivery is complete.
 * <p>
 * This is deployment, not mapping. Which files arrive and what they are called
 * is a property of the system on the other end of the directory, and it differs
 * between test and production while the mapping does not - so it lives in the
 * feed's {@value #FILE} rather than in the spec, and this module owns it.
 * Nothing in {@code spec}, {@code ia} or {@code ldr} has any use for it.
 * <p>
 * There are exactly two ways to deliver, and they are the two subtypes rather
 * than two nullable fields:
 * <ul>
 *   <li>{@link Atomic} - the data file itself is moved into {@code in/}, and its
 *       appearance is the signal. The move must be atomic, which is why a
 *       producer moves rather than writes in place.</li>
 *   <li>{@link Signalled} - the data file may be written in place, and a marker
 *       file moved in afterwards says it is complete.</li>
 * </ul>
 * A feed declares one or the other. Saying both would be asking for the file to
 * be claimed twice by two rules, and saying neither leaves no way to tell a
 * finished delivery from one still being written - so "exactly one" is the shape
 * of this type and not a check somewhere else.
 */
public sealed interface Delivery {

    /**
     * The file, beside the mapping spec in the feed directory. Its presence is
     * what makes the directory a feed at all.
     */
    String FILE = "delivery.properties";

    String ACCEPTS = "accepts";
    String SENTINEL = "sentinel";

    /**
     * Every setting this file may carry. Anything else is refused rather than
     * ignored: a properties file has no schema to catch a misspelling, and
     * {@code acccepts} silently skipped would leave a feed claiming nothing with
     * nothing to say about why.
     */
    Set<String> SETTINGS = Set.of(ACCEPTS, SENTINEL);

    /**
     * Reads a feed's delivery file.
     *
     * @param file the {@value #FILE} of a feed directory
     * @throws IOException              if it cannot be read
     * @throws IllegalArgumentException if it carries an unknown setting, does not
     *                                  declare exactly one way of delivering, or
     *                                  names a pattern that will not compile
     */
    static Delivery read(Path file) throws IOException {
        var props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        return of(props);
    }

    /**
     * The same, from properties already in hand - which is how it is tested, and
     * how a caller with another source of settings could use it.
     */
    static Delivery of(Properties props) {
        var unknown = new TreeSet<>(props.stringPropertyNames());
        unknown.removeAll(SETTINGS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(FILE + " does not know the setting(s) " + unknown
                    + "; it reads " + new TreeSet<>(SETTINGS));
        }
        var accepts = setting(props, ACCEPTS);
        var sentinel = setting(props, SENTINEL);
        // written as two positive cases rather than one negative one: this way
        // each branch proves its own argument non-null, and nobody is later
        // invited to delete a check the compiler cannot see the need for
        if (accepts != null && sentinel == null) {
            return Atomic.parse(accepts);
        } else if (sentinel != null && accepts == null) {
            return new Signalled(Sentinel.parse(sentinel));
        } else {
            throw new IllegalArgumentException(FILE + " must declare exactly one of '"
                    + ACCEPTS + "' or '" + SENTINEL + "', found "
                    + (accepts == null ? "neither" : "both"));
        }
    }

    /**
     * A setting that is present but blank counts as absent, so that a half-edited
     * {@code accepts=} is reported as the missing choice it is rather than as a
     * pattern that matches nothing. The value is stripped, since
     * {@link Properties#load} keeps trailing whitespace and it would otherwise
     * end up inside the pattern.
     */
    private static @Nullable String setting(Properties props, String name) {
        var value = props.getProperty(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Whether this feed claims {@code file} - asked of a file that has arrived in
     * {@code in/}, and answered from its name alone.
     */
    boolean claims(Path file);

    /**
     * Delivery by atomic move: the data file appears complete or not at all.
     *
     * @param pattern the {@code glob:} or {@code regex:} form, kept for messages
     * @param matcher that pattern compiled, matched against the file name only
     */
    record Atomic(String pattern, PathMatcher matcher) implements Delivery {

        static Atomic parse(String pattern) {
            if (!pattern.startsWith("glob:") && !pattern.startsWith("regex:")) {
                throw new IllegalArgumentException(
                        ACCEPTS + " must start with 'glob:' or 'regex:', was: " + pattern);
            }
            try {
                return new Atomic(pattern, FileSystems.getDefault().getPathMatcher(pattern));
            } catch (PatternSyntaxException | UnsupportedOperationException e) {
                throw new IllegalArgumentException("invalid " + ACCEPTS + " pattern: " + pattern, e);
            }
        }

        @Override
        public boolean claims(Path file) {
            return matcher.matches(file.getFileName());
        }

        @Override
        public String toString() {
            return ACCEPTS + "=" + pattern;
        }
    }

    /**
     * Delivery signalled by a marker: the data file may be written in place, and
     * the marker that follows it says so.
     * <p>
     * What is claimed here is the <em>marker</em>. The data file it names is not
     * matched against anything - the marker having vouched for it - which is why
     * this asks {@link Sentinel#isMarker} rather than a pattern of its own.
     */
    record Signalled(Sentinel sentinel) implements Delivery {

        @Override
        public boolean claims(Path file) {
            return sentinel.isMarker(file);
        }

        @Override
        public String toString() {
            return SENTINEL + "=" + sentinel;
        }
    }
}
