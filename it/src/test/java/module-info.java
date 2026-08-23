open module io.github.ralfspoeth.xldr.it.test {
    // require the core parts
    requires transitive io.github.ralfspoeth.xldr.it;
    // CheckIT drives the shipped command line rather than the class behind it,
    // so that what is tested is what an author actually types
    requires io.github.ralfspoeth.xldr.app;
    requires info.picocli;
    // XldrServletIT deploys the servlet into an embedded Jetty and talks to it
    // with the JDK's own HTTP client. It lives here rather than in xlet because
    // that module is published: a consumer reading its pom should not find a
    // container in it
    requires io.github.ralfspoeth.xldr.xlet;
    requires jakarta.servlet;
    requires org.eclipse.jetty.ee11.servlet;
    requires org.eclipse.jetty.server;
    requires java.net.http;
    // the conformance kit, and the five adapters run against it. These tests live
    // here rather than in each adapter's own module because those are patched
    // into the module they test and so have no descriptor to require the kit in
    requires io.github.ralfspoeth.xldr.tck;
    // require the adapters
    requires io.github.ralfspoeth.xldr.xml;
    requires io.github.ralfspoeth.xldr.csv;
    requires io.github.ralfspoeth.xldr.flt;
    requires io.github.ralfspoeth.xldr.json;
    requires io.github.ralfspoeth.xldr.xlsx;
    // testing
    requires org.junit.jupiter.api;
    requires org.apache.poi.ooxml;

    // we certainly use the factory;
    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
    uses java.sql.Driver;
}
