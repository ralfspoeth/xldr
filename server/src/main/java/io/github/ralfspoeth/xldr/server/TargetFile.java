package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.xldr.ldr.Target;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reading a feed's {@value #FILE}, the dual of {@value Delivery#FILE}.
 * <p>
 * One says how a feed's files arrive; this one says where their rows go. Both
 * are deployment properties rather than mapping ones, which is why neither is in
 * the spec: a spec is meant to travel from test to production unchanged, and the
 * schema it lands in is exactly the kind of thing that differs between the two.
 *
 * <pre>
 * schema  = staging
 * catalog = warehouse
 * </pre>
 *
 * Both settings are optional and the file itself is optional. A feed without one
 * loads into whatever the connection's own search path finds, which is how every
 * feed worked before this existed and how most will go on working.
 */
final class TargetFile {

    /** read from the feed directory, beside the spec */
    static final String FILE = "target.properties";

    static final String SCHEMA = "schema";
    static final String CATALOG = "catalog";
    private static final List<String> SETTINGS = List.of(SCHEMA, CATALOG);

    private TargetFile() {
    }

    /**
     * @param feedDirectory the feed whose {@value #FILE} to read
     * @return what it says, or {@link Target#none()} where there is no such file
     * @throws IOException              if the file exists but cannot be read
     * @throws IllegalArgumentException if it carries a setting this does not know
     */
    static Target read(Path feedDirectory) throws IOException {
        var file = feedDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Target.none();
        }
        var props = new Properties();
        // UTF-8 rather than the ISO-8859-1 the stream overload assumes, as with
        // env.properties: these are written by hand and reach SQL verbatim
        try (var in = Files.newBufferedReader(file, UTF_8)) {
            props.load(in);
        }
        return of(props);
    }

    /**
     * The same, from properties already in hand - which is how it is tested.
     * <p>
     * An unknown setting is refused rather than ignored, exactly as
     * {@link Delivery} refuses one and for a sharper reason: a misspelled
     * {@code schmea} would leave the load unqualified, and an unqualified load
     * against a connection whose search path happens to find a table of the same
     * name succeeds - into the wrong schema, silently, which is the worst outcome
     * this file can have.
     */
    static Target of(Properties props) {
        var unknown = new TreeSet<>(props.stringPropertyNames());
        unknown.removeAll(SETTINGS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(FILE + " does not know the setting(s) " + unknown
                    + "; it reads " + new TreeSet<>(SETTINGS)
                    + ". A misspelled one would leave the load unqualified, which against the wrong"
                    + " search path succeeds into the wrong schema");
        }
        return new Target(setting(props, CATALOG), setting(props, SCHEMA));
    }

    /**
     * A setting that is present but blank counts as absent, so a half-edited
     * {@code schema=} is no schema rather than one named the empty string. The
     * value is stripped, {@link Properties#load} keeping trailing whitespace that
     * would otherwise end up inside an identifier.
     */
    private static @Nullable String setting(Properties props, String name) {
        var value = props.getProperty(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
