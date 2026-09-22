package io.github.ralfspoeth.xldr.server;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * The three settings that say which database to talk to, read out of an
 * {@code xldr.properties} without the rest of it.
 *
 * <pre>
 * jdbc.url      = jdbc:oracle:thin:@//host:1521/sid
 * jdbc.user     = dbuser
 * jdbc.password = secret
 * </pre>
 *
 * This exists so that the three key names have one home. {@link Config} turns
 * them into the pool's {@code jdbcUrl} / {@code username} / {@code password},
 * and {@code xldr check} reads the same three to find the database a deployment
 * feeds - two readers of one file, and a rename that reached only one of them
 * would be the kind of drift this project has been bitten by before.
 * <p>
 * <strong>Why {@code check} does not simply call {@link Config#load}.</strong>
 * {@code Config} requires {@code xldr.roots}, and requires the directories it
 * names to be meaningful on this machine. That is right for a server about to
 * watch them and wrong for a command that only wants a connection: a spec is
 * often checked on a laptop against a configuration copied from a host whose
 * feed roots are nowhere to be found locally, and refusing to check it for that
 * reason would be refusing for a reason the user cannot act on.
 *
 * @param url      the JDBC URL; the one setting without which there is nothing
 * @param user     the user, where the file names one
 * @param password the password, where the file names one
 */
public record Jdbc(String url, @Nullable String user, @Nullable String password) {

    /**
     * The three names, public because they are the documented spelling of a
     * configuration file rather than an implementation detail - {@code xldr check}
     * names {@code jdbc.url} in a message when it cannot find one, and a message
     * that spelled it independently would be free to spell it wrong.
     */
    public static final String URL_KEY = "jdbc.url";

    public static final String USER_KEY = "jdbc.user";

    public static final String PASSWORD_KEY = "jdbc.password";

    public Jdbc {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(URL_KEY + " is required and was " + url);
        }
    }

    /**
     * @param props settings as they would be read from an {@code xldr.properties}
     * @return the connection settings in them, or empty where there is no
     * {@code jdbc.url} - which is a file that says nothing about a database
     * rather than a file that is wrong, and is the caller's business to report
     */
    public static Optional<Jdbc> in(Properties props) {
        var url = strip(props.getProperty(URL_KEY));
        return url == null
                ? Optional.empty()
                : Optional.of(new Jdbc(url, strip(props.getProperty(USER_KEY)),
                        strip(props.getProperty(PASSWORD_KEY))));
    }

    /**
     * The same, from a file that may not be there.
     *
     * @param propertiesFile an {@code xldr.properties}, existing or not
     * @return its connection settings, or empty if the file is absent or names
     * no {@code jdbc.url}
     * @throws IOException if the file is there and cannot be read - which is
     *                     worth reporting, being a file the caller pointed at
     */
    public static Optional<Jdbc> in(Path propertiesFile) throws IOException {
        if (!Files.isRegularFile(propertiesFile)) {
            return Optional.empty();
        }
        var props = new Properties();
        try (var in = Files.newBufferedReader(propertiesFile)) {
            props.load(in);
        }
        return in(props);
    }

    /**
     * Never the password, and never the URL's query string, which is where some
     * drivers accept one. What a caller wants to print is which database was
     * reached, and that is the part before the question mark.
     *
     * @return the URL as it is safe to show
     */
    public String displayUrl() {
        var query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query) + "?...";
    }

    private static @Nullable String strip(@Nullable String value) {
        if (value == null) {
            return null;
        }
        var stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
