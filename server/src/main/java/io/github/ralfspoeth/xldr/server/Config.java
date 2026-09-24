package io.github.ralfspoeth.xldr.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Server configuration: the directory tree to watch and the single target
 * database this process feeds.
 * <p>
 * Connection settings live here rather than in the mapping specs so that a spec
 * can be promoted from test to production unchanged, and so that credentials
 * stay out of the watched input tree.
 *
 * <pre>
 * xldr.roots             = /var/lib/xldr:/mnt/feeds
 * xldr.scanInterval      = 30
 * xldr.maxConcurrentLoads = 4
 * jdbc.url      = jdbc:oracle:thin:@//host:1521/sid
 * jdbc.user     = dbuser
 * jdbc.password = secret
 * </pre>
 *
 * The roots are the only directories in which feeds may be created; they are
 * separated by the platform path separator and have to exist at startup, since
 * nothing watches their parents. Every {@code pool.*} key is passed straight to
 * {@code HikariConfig} under its own name, so the whole pool configuration is
 * reachable without this class having to mirror it.
 * <p>
 * How many loads run at once is said once, by {@code xldr.maxConcurrentLoads},
 * and the pool is sized to follow it. Two numbers for one thing would only give
 * the pool the chance to be the lower of them, at which point the surplus loads
 * would queue in {@code getConnection()} rather than anywhere a reader of the
 * configuration would look.
 *
 * @param roots                the directories in which feeds may be created
 * @param scanIntervalSeconds  how often the whole tree is reconciled; watch
 *                             events only make the reaction quicker
 * @param maxConcurrentLoads   how many files may be loaded at the same time, and
 *                             so how large the pool is unless
 *                             {@code pool.maximumPoolSize} says otherwise
 * @param poolProperties       the connection pool settings, under the names
 *                             {@code HikariConfig} knows them by
 */
public record Config(
        List<Path> roots,
        long scanIntervalSeconds,
        int maxConcurrentLoads,
        Properties poolProperties
) {

    private static final String POOL_PREFIX = "pool.";
    /**
     * Public for the same reason {@link Jdbc#URL_KEY} is: it is the documented
     * spelling of a configuration file rather than an implementation detail, and
     * {@code xldr check} names it when a deployment's roots are not where the
     * file says. A message that spelled it independently would be free to spell
     * it wrong.
     */
    public static final String ROOTS_KEY = "xldr.roots";
    private static final String SCAN_KEY = "xldr.scanInterval";
    private static final String CONCURRENCY_KEY = "xldr.maxConcurrentLoads";
    private static final String MAX_POOL_SIZE = "maximumPoolSize";
    private static final long DEFAULT_SCAN_INTERVAL = 30L;
    private static final int DEFAULT_MAX_CONCURRENT_LOADS = 4;

    public Config {
        roots = List.copyOf(roots);
    }

    /**
     * Reads the file, resolving any relative {@code xldr.roots} against the
     * directory the file is in.
     * <p>
     * A path written in a configuration file means a path relative to that file.
     * It used to mean relative to whatever directory the process happened to be
     * started from, which agreed with the above whenever a server was started by
     * {@code cd}-ing to its configuration - and disagreed silently the moment
     * anything pointed at a configuration somewhere else. {@code xldr check
     * --dir /etc/xldr} is exactly that, and would look for a relative root under
     * the caller's own directory instead. An absolute root is unaffected, which
     * is every root in a deployment that copied the shipped sample.
     *
     * @param propertiesFile the server configuration file
     * @return the configuration it describes
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if a required setting is missing or a
     *                                  value does not make sense
     */
    public static Config load(Path propertiesFile) throws IOException {
        var props = new Properties();
        try (var in = Files.newBufferedReader(propertiesFile)) {
            props.load(in);
        }
        var parent = propertiesFile.toAbsolutePath().getParent();
        return of(props, parent == null ? Path.of("") : parent);
    }

    /**
     * @param props the server settings, as they would be read from the file
     * @return the configuration they describe, with relative roots resolved
     * against the working directory - there being no file to resolve against
     * @throws IllegalArgumentException if a required setting is missing or a
     *                                  value does not make sense
     */
    public static Config of(Properties props) {
        return of(props, Path.of(""));
    }

    /**
     * @param props the server settings
     * @param base  what a relative {@code xldr.roots} entry is relative to,
     *              normally the directory holding the file they were read from
     * @return the configuration they describe
     * @throws IllegalArgumentException if a required setting is missing or a
     *                                  value does not make sense
     */
    public static Config of(Properties props, Path base) {
        var roots = Stream.of(require(props, ROOTS_KEY).split(Pattern.quote(File.pathSeparator)))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(Path::of)
                // resolve leaves an absolute path alone, so this is the relative
                // case only
                .map(base::resolve)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(ROOTS_KEY + " names no directory");
        }
        var scanInterval = Optional.ofNullable(props.getProperty(SCAN_KEY))
                .map(String::strip)
                .map(Long::parseLong)
                .orElse(DEFAULT_SCAN_INTERVAL);
        int maxConcurrentLoads = Optional.ofNullable(props.getProperty(CONCURRENCY_KEY))
                .map(String::strip)
                .map(Integer::parseInt)
                .orElse(DEFAULT_MAX_CONCURRENT_LOADS);
        if (maxConcurrentLoads < 1) {
            throw new IllegalArgumentException(CONCURRENCY_KEY + " must be at least 1");
        }

        // the three jdbc.* names live on Jdbc, which xldr check reads too; a
        // server that cannot say which database it feeds is not startable, so
        // what is optional there is required here
        var jdbc = Jdbc.in(props).orElseThrow(() ->
                new IllegalArgumentException(Jdbc.URL_KEY + " is required"));
        var pool = new Properties();
        pool.setProperty("jdbcUrl", jdbc.url());
        if (jdbc.user() != null) {
            pool.setProperty("username", jdbc.user());
        }
        if (jdbc.password() != null) {
            pool.setProperty("password", jdbc.password());
        }
        for (var name : props.stringPropertyNames()) {
            if (name.startsWith(POOL_PREFIX)) {
                pool.setProperty(name.substring(POOL_PREFIX.length()), props.getProperty(name));
            }
        }
        // A load borrows exactly one connection for the duration of one file, so
        // this many is enough for every load that may run at once and the pool
        // never becomes a second, lower limit that no setting names. Hikari's own
        // default of ten would silently cap - or needlessly exceed - the
        // concurrency actually configured. An explicit pool.maximumPoolSize still
        // wins, for a database that will not have that many sessions.
        pool.putIfAbsent(MAX_POOL_SIZE, String.valueOf(maxConcurrentLoads));
        return new Config(roots, scanInterval, maxConcurrentLoads, pool);
    }

    private static String require(Properties props, String key) {
        var value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing configuration property " + key);
        }
        return value;
    }
}
