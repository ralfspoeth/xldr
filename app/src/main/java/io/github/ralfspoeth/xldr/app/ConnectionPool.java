package io.github.ralfspoeth.xldr.app;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ralfspoeth.xldr.server.Config;
import io.github.ralfspoeth.xldr.server.ConnectionSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The pooled connection source for the one target database of this server.
 */
final class ConnectionPool implements ConnectionSource, AutoCloseable {

    private final HikariDataSource dataSource;

    public ConnectionPool(Config config) {
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
