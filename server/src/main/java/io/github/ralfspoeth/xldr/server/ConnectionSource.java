package io.github.ralfspoeth.xldr.server;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Hands out connections to the one target database this server feeds.
 * <p>
 * Deliberately narrower than {@code DataSource}: it is the only thing a
 * {@link LoadJob} needs, it can be written as a lambda in tests, and a pooled
 * {@code DataSource} can be adopted later with {@code dataSource::getConnection}
 * without touching any caller.
 */
@FunctionalInterface
public interface ConnectionSource {
    Connection getConnection() throws SQLException;
}
