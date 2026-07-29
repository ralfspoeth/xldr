/**
 * The server: watches the configured roots and loads the files that appear in
 * the feeds below them.
 */
module io.github.ralfspoeth.xldr.app {
    exports io.github.ralfspoeth.xldr.app;
    opens io.github.ralfspoeth.xldr.app to info.picocli;

    requires io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.xldr.ldr;
    requires java.sql;
    requires java.logging;
    // the JMX statistics, which need no agent and no dependency
    requires java.management;
    requires com.zaxxer.hikari;
    requires io.github.ralfspoeth.filews;
    requires info.picocli;
    requires org.slf4j.jul;

    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
}
