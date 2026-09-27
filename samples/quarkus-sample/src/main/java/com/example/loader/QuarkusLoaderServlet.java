package com.example.loader;

import io.github.ralfspoeth.xldr.xlet.XldrServlet;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;

import javax.sql.DataSource;

/**
 * xlet under Quarkus.
 *
 * <p>Two things differ from the war samples, and the second is why this one had
 * to wait for a change to {@code xlet} itself.
 *
 * <p><strong>The {@code DataSource} is injected.</strong> Quarkus has no JNDI,
 * so the lookup the default {@code dataSource()} performs would find nothing.
 * Overriding it is the whole of the fix - the same {@code protected} seam the
 * Spring sample uses and the same four lines, with {@code @Inject} where Spring
 * had a constructor. Its javadoc anticipates this case: *a deployment that has
 * its DataSource from Spring, from a CDI producer or from anywhere else
 * overrides this and never touches a directory.*
 *
 * <p><strong>The specs come from a directory.</strong> A Quarkus application is
 * an executable jar with no {@code /WEB-INF/}, so
 * {@code ServletContext.getResourcePaths} has nothing to answer with and the
 * {@code specs} parameter names a directory instead. That directory is outside
 * the artifact and outside whatever protects it, which is the trade: under
 * {@code /WEB-INF/} the container's access control covered the specs, and here
 * it is yours to arrange. Table and column names reach the SQL unbound, so
 * whatever can write there decides what this application runs.
 *
 * <p>The value below is relative, which is right for a sample run from its own
 * directory and wrong for a deployment. Name an absolute path in one, beside
 * the application's own configuration and owned as tightly.
 */
@WebServlet(
        name = "xldr",
        urlPatterns = "/load",
        // read the specs and settle everything at startup rather than on the
        // first request, so a mistake is a failure to start and not a 500
        loadOnStartup = 1,
        initParams = {
                @WebInitParam(name = "specs", value = "specs"),
                // what ${env.source} in the spec resolves to, as a feed's
                // env.properties would supply it under the file server
                @WebInitParam(name = "env.source", value = "quarkus")
        })
public class QuarkusLoaderServlet extends XldrServlet {

    @Inject
    DataSource dataSource;

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }
}
