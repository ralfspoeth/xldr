package com.pd.xldr.app;

import com.pd.xldr.spec.OutputSpec;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * Resolves the JNDI name carried by an {@link OutputSpec} to a live
 * {@link DataSource}.
 * <p>
 * This is the one place in the toolkit that knows about JNDI: the spec modules
 * treat the data source as an opaque name so that a mapping spec stays plain,
 * serializable configuration.
 */
public final class DataSources {

    private static final String JAVA_COMP_ENV = "java:comp/env/";

    private DataSources() {
    }

    /**
     * Looks up the data source named by {@code spec}. Names are tried as given
     * first, then below {@code java:comp/env/}, so both a fully qualified name
     * and a plain one such as {@code jdbc/h2/test} work.
     */
    public static DataSource lookup(OutputSpec spec) throws NamingException {
        var name = spec.dataSource();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("no data source name in the output spec");
        }
        var ctx = new InitialContext();
        try {
            return (DataSource) ctx.lookup(name);
        } catch (NamingException first) {
            if (name.startsWith(JAVA_COMP_ENV) || name.startsWith("java:")) {
                throw first;
            }
            try {
                return (DataSource) ctx.lookup(JAVA_COMP_ENV + name);
            } catch (NamingException second) {
                second.addSuppressed(first);
                throw second;
            }
        } finally {
            try {
                ctx.close();
            } catch (NamingException ignored) {
                // the looked-up data source stays usable
            }
        }
    }
}
