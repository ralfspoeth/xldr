package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.Map;

import static java.util.Objects.requireNonNullElse;

/**
 * Names the target of a load.
 *
 * @param dataSource the JNDI name of the target {@code DataSource}, such as
 *                   {@code jdbc/h2/test} or {@code jdbc/prod/orcl}. Resolving
 *                   the name is the responsibility of the application; this
 *                   module stays free of any JDBC dependency so that a mapping
 *                   spec remains plain, serializable configuration.
 * @param info       additional, driver specific properties
 */
public record OutputSpec(String dataSource, Map<String, String> info) implements Serializable {
    public OutputSpec {
        info = Map.copyOf(requireNonNullElse(info, Map.of()));
    }
}
