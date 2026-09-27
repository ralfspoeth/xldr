package com.example.loader;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * The table {@code specs/customers.json} loads into, created at startup.
 *
 * <p>The sample's database is an in-memory H2 that exists only while the
 * application runs, so something has to create the table and it may as well be
 * visible. A real deployment has this from a migration tool or from whoever
 * owns the schema, and would delete this class.
 *
 * <p>It is a startup observer rather than an {@code INIT} clause on the JDBC
 * URL - which is how {@code jetty-sample} does it, in a file that is already
 * Jetty-specific XML. Here the URL lives in {@code application.properties},
 * where a comma inside a value is ambiguous to config parsing, and DDL hidden
 * in a connection string is DDL nobody reading the code will find.
 */
@ApplicationScoped
public class Schema {

    @Inject
    DataSource dataSource;

    void createTheTable(@Observes StartupEvent ignored) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists customer(
                        id integer, name varchar(50), source varchar(20))
                    """);
        }
    }
}
