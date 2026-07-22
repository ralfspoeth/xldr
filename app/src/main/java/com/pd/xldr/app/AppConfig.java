package com.pd.xldr.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Server configuration: the directory tree to watch and the single target
 * database this process feeds.
 * <p>
 * Connection settings live here rather than in the mapping specs so that a spec
 * can be promoted from test to production unchanged, and so that credentials
 * stay out of the watched input tree.
 *
 * <pre>
 * xldr.root     = /var/lib/xldr
 * jdbc.url      = jdbc:oracle:thin:@//host:1521/sid
 * jdbc.user     = dbuser
 * jdbc.password = secret
 * pool.maximumPoolSize = 4
 * </pre>
 *
 * Every {@code pool.*} key is passed straight to {@code HikariConfig} under its
 * own name, so the whole pool configuration is reachable without this class
 * having to mirror it.
 */
public record AppConfig(Path root, Properties poolProperties) {

    private static final String POOL_PREFIX = "pool.";
    private static final String ROOT_KEY = "xldr.root";
    private static final String URL_KEY = "jdbc.url";
    private static final String USER_KEY = "jdbc.user";
    private static final String PASSWORD_KEY = "jdbc.password";

    public static AppConfig load(Path propertiesFile) throws IOException {
        var props = new Properties();
        try (var in = Files.newBufferedReader(propertiesFile)) {
            props.load(in);
        }
        return of(props);
    }

    public static AppConfig of(Properties props) {
        var root = Path.of(require(props, ROOT_KEY));

        // HikariConfig(Properties) assigns by bean property name
        var pool = new Properties();
        pool.setProperty("jdbcUrl", require(props, URL_KEY));
        copyIfPresent(props, USER_KEY, pool, "username");
        copyIfPresent(props, PASSWORD_KEY, pool, "password");
        for (var name : props.stringPropertyNames()) {
            if (name.startsWith(POOL_PREFIX)) {
                pool.setProperty(name.substring(POOL_PREFIX.length()), props.getProperty(name));
            }
        }
        return new AppConfig(root, pool);
    }

    private static void copyIfPresent(Properties from, String key, Properties to, String targetKey) {
        var value = from.getProperty(key);
        if (value != null) {
            to.setProperty(targetKey, value);
        }
    }

    private static String require(Properties props, String key) {
        var value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing configuration property " + key);
        }
        return value;
    }

    public String jdbcUrl() {
        return poolProperties.getProperty("jdbcUrl");
    }
}
