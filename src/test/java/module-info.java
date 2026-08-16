/**
 * Open, because JUnit reflects on the tests. Nothing else here needs it: the
 * servlet API is faked with java.lang.reflect proxies, which ask no favours of
 * the module system.
 */
open module io.github.ralfspoeth.xldr.xlet.test {

    requires transitive io.github.ralfspoeth.xldr.xlet;
    requires jakarta.servlet;
    requires org.junit.jupiter.api;

    // the one test that really loads: an in-memory database reached through
    // DriverManager, so that nothing here depends on a vendor's types
    requires java.sql;

    // and an adapter for it to read with. Named here rather than used directly:
    // requiring it is what puts it in the graph, and the lookup inside ia then
    // finds it - which is exactly how a deployment chooses its formats
    requires io.github.ralfspoeth.xldr.csv;

    // the integration test runs the servlet in an embedded container, and talks
    // to it with the JDK's own HTTP client
    requires org.eclipse.jetty.ee11.servlet;
    requires org.eclipse.jetty.server;
    requires java.net.http;
}
