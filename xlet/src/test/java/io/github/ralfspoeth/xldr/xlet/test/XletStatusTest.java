package io.github.ralfspoeth.xldr.xlet.test;

import io.github.ralfspoeth.xldr.xlet.XldrServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statistics, read the way a monitor reads them: off the platform
 * {@code MBeanServer}, by object name, one attribute at a time.
 * <p>
 * Asserting on the {@code XletStatus} object directly would be easier and would
 * prove less. What is worth proving is that the bean is registered, that its
 * name distinguishes this deployment from another one, that the attributes are
 * the shape JMX can carry - a record becomes {@code CompositeData}, a map
 * becomes {@code TabularData}, and either can fail at registration for reasons a
 * direct call would never meet - and that {@code destroy} takes it away again.
 */
class XletStatusTest {

    private static final String JDBC_URL = "jdbc:h2:mem:xletstatus;DB_CLOSE_DELAY=-1";

    private static final String PEOPLE_SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "properties": { "fieldSeparator": "," },
                "recordSelectors": [
                  { "name": "people", "fieldSelectors": [
                      {"name": "id",   "selector": "id",   "type": "TEXT"},
                      {"name": "name", "selector": "name", "type": "TEXT"}
                  ] }
                ]
              },
              "mapping": [
                { "recordSelector": "people", "table": "person", "fieldMapping": [
                    {"fieldSelector": "id",   "column": "id"},
                    {"fieldSelector": "name", "column": "name"}
                ] }
              ]
            }
            """;

    private static final byte[] TWO_PEOPLE = """
            id,name
            1,Alice
            2,Bob
            """.getBytes(UTF_8);

    private static final MBeanServer MBEANS = ManagementFactory.getPlatformMBeanServer();

    private static class Testable extends XldrServlet {
        private final DataSource dataSource;

        Testable(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        protected @NonNull DataSource dataSource() {
            return dataSource;
        }

        void post(HttpServletRequest request, Proxies.Recorded response) throws IOException {
            doPost(request, response.response());
        }
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists person");
            stmt.execute("create table person(id varchar(10), name varchar(50))");
        }
    }

    private static Testable started(String contextPath, Map<String, String> initParams, String jdbcUrl)
            throws ServletException {
        var servlet = new Testable(Proxies.dataSource(jdbcUrl).dataSource());
        ServletConfig config = Proxies.config(
                Proxies.context(Map.of(Proxies.SPECS + "people.json", PEOPLE_SPEC), Map.of(), contextPath),
                initParams);
        servlet.init(config);
        return servlet;
    }

    private static ObjectName nameOf(String contextPath) throws Exception {
        return new ObjectName("io.github.ralfspoeth.xldr:type=Loader"
                + ",context=" + ObjectName.quote(contextPath)
                + ",name=" + ObjectName.quote("xldr"));
    }

    private static Object attribute(String contextPath, String name) throws Exception {
        return MBEANS.getAttribute(nameOf(contextPath), name);
    }

    /**
     * A load moves the totals and the spec's own row together, which is the
     * invariant worth pinning: the table is what an operator reads to find out
     * which spec the totals came from, and one that did not add up would send
     * them looking in the wrong place.
     */
    @Test
    void countsALoadInTheTotalsAndAgainstItsSpec() throws Exception {
        var context = "/counts";
        var servlet = started(context, Map.of(), JDBC_URL);
        try {
            servlet.post(Proxies.post("text/csv", Map.of("spec", "people"), TWO_PEOPLE), new Proxies.Recorded());

            assertAll(
                    () -> assertEquals(1L, attribute(context, "LoadsSucceeded")),
                    () -> assertEquals(0L, attribute(context, "LoadsFailed")),
                    () -> assertEquals(2L, attribute(context, "RecordsLoaded")),
                    () -> assertEquals(0, attribute(context, "LoadsInProgress"), "and none is still running"),
                    () -> assertFalse(String.valueOf(attribute(context, "LastLoad")).isEmpty()));
        } finally {
            servlet.destroy();
        }
    }

    /**
     * A refusal is not a failed load. The two counters answer different
     * questions - whether callers are sending the wrong thing, and whether this
     * deployment is breaking - and an operator who could not tell them apart
     * would go looking at the database for what is a client's mistake.
     */
    @Test
    void countsARefusalApartFromAFailure() throws Exception {
        var context = "/refusals";
        var servlet = started(context, Map.of(), null);
        try {
            servlet.post(Proxies.post("application/json", Map.of("spec", "people"), TWO_PEOPLE),
                    new Proxies.Recorded());

            assertAll(
                    () -> assertEquals(1L, attribute(context, "RequestsRefused")),
                    () -> assertEquals(0L, attribute(context, "LoadsFailed")),
                    () -> assertEquals(0L, attribute(context, "LoadsSucceeded")),
                    () -> assertEquals(0L, attribute(context, "LoadsRejected")));
        } finally {
            servlet.destroy();
        }
    }

    /**
     * And a rejection is neither. With no permits at all every request is turned
     * away at once, which is the overload path made deterministic - and the
     * number an operator judges the concurrency settings by, which is why those
     * settings are exposed beside it.
     */
    @Test
    void countsARejectionAndShowsTheSettingsToJudgeItBy() throws Exception {
        var context = "/rejections";
        var servlet = started(context, Map.of("maxConcurrentLoads", "0", "acquireTimeoutMillis", "0"), null);
        try {
            servlet.post(Proxies.post("text/csv", Map.of("spec", "people"), TWO_PEOPLE), new Proxies.Recorded());

            assertAll(
                    () -> assertEquals(1L, attribute(context, "LoadsRejected")),
                    () -> assertEquals(0L, attribute(context, "RequestsRefused"), "the request was fine"),
                    () -> assertEquals(0L, attribute(context, "LoadsSucceeded")),
                    () -> assertEquals(0, attribute(context, "MaxConcurrentLoads")),
                    () -> assertEquals(0L, attribute(context, "AcquireTimeoutMillis")));
        } finally {
            servlet.destroy();
        }
    }

    /**
     * Every deployed spec has a row, loaded or not. A spec that has never loaded
     * is the row worth seeing - it is either unused or unreachable, and neither
     * shows in a total.
     */
    @Test
    void listsEverySpecTheDeploymentCarries() throws Exception {
        var context = "/specs";
        var servlet = started(context, Map.of(), null);
        try {
            var table = attribute(context, "Specs");
            assertTrue(String.valueOf(table).contains("people"), String.valueOf(table));
        } finally {
            servlet.destroy();
        }
    }

    /**
     * Two deployments of the same WAR, which is the case a fixed object name gets
     * wrong: the second registration would be refused and the second deployment
     * would report the first one's numbers.
     */
    @Test
    void twoDeploymentsBothRegister() throws Exception {
        var first = started("/one", Map.of(), JDBC_URL);
        var second = started("/two", Map.of(), JDBC_URL);
        try {
            first.post(Proxies.post("text/csv", Map.of("spec", "people"), TWO_PEOPLE), new Proxies.Recorded());

            assertAll(
                    () -> assertTrue(MBEANS.isRegistered(nameOf("/one"))),
                    () -> assertTrue(MBEANS.isRegistered(nameOf("/two"))),
                    () -> assertEquals(1L, attribute("/one", "LoadsSucceeded")),
                    () -> assertEquals(0L, attribute("/two", "LoadsSucceeded"), "and they count separately"));
        } finally {
            first.destroy();
            second.destroy();
        }
    }

    /**
     * Undeployment takes the bean with it. Left behind, it holds a strong
     * reference to a class loaded by the web application's own loader, and every
     * redeploy leaks that loader and everything under it.
     */
    @Test
    void destroyUnregistersTheBean() throws Exception {
        var context = "/undeployed";
        var servlet = started(context, Map.of(), null);
        assertTrue(MBEANS.isRegistered(nameOf(context)), "registered while deployed");

        servlet.destroy();
        assertFalse(MBEANS.isRegistered(nameOf(context)), "and gone afterwards");
    }
}
