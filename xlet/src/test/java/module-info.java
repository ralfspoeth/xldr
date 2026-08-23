/**
 * Open, because JUnit reflects on the tests. Nothing else here needs it: the
 * servlet API is stood in for by java.lang.reflect proxies, which ask no favours
 * of the module system.
 * <p>
 * Proxies all the way down, and deliberately - the one test that runs the servlet
 * in a real container is {@code XldrServletIT} in the {@code it} module, which is
 * where the embedded Jetty and the HTTP client live too.
 * <p>
 * A module of its own, where every library module's tests are now patched into
 * the module they test, and for a reason that has nowhere else to live: the
 * {@code requires} on {@code csv} below. An adapter is found by service binding
 * over the module graph - the jars carry {@code provides} in their descriptors
 * and no {@code META-INF/services} - so an adapter reaches the graph only by
 * being required by something. {@code xlet} itself must not require one, a front
 * end having no business choosing formats, and a patched test cannot add a
 * {@code requires} because it has no descriptor. This file is the only place
 * that can say it, and without it most of {@code XldrServletTest} posts
 * {@code text/csv} at a servlet that can find no adapter for it.
 */
open module io.github.ralfspoeth.xldr.xlet.test {

    requires transitive io.github.ralfspoeth.xldr.xlet;
    requires jakarta.servlet;
    requires org.junit.jupiter.api;

    // named here rather than inherited: the main module's requires is static and
    // therefore not transitive, so requiring xlet brings no annotations along
    requires static org.jspecify;

    // the one test that really loads: an in-memory database reached through
    // DriverManager, so that nothing here depends on a vendor's types
    requires java.sql;

    // the statistics are read the way a monitor reads them, off the platform
    // MBeanServer by object name, rather than off the object behind them
    requires java.management;

    // and an adapter for it to read with. Named here rather than used directly:
    // requiring it is what puts it in the graph, and the lookup inside ia then
    // finds it - which is exactly how a deployment chooses its formats
    requires io.github.ralfspoeth.xldr.csv;
}
