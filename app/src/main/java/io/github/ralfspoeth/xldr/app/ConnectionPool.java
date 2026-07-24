package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.ldr.Loader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The pooled connection source for the one target database of this server.
 * <p>
 * Owned by the server process: created once at startup and closed on shutdown -
 * not per load. A {@link Loader} borrows a connection for the duration of one
 * input file and returns it by closing it, which is why the loader restores the
 * auto-commit flag it found.
 */
public final class ConnectionPool implements ConnectionSource, AutoCloseable {

    private final HikariDataSource dataSource;

    public ConnectionPool(AppConfig config) {
        this.dataSource = new HikariDataSource(new HikariConfig(config.poolProperties()));
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
