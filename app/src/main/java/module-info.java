import org.jspecify.annotations.NullMarked;

/**
 * The server as it is shipped: a command line, a connection pool and a logging
 * setup around {@link io.github.ralfspoeth.xldr.server.Watcher}.
 * <p>
 * These are the decisions a runner makes rather than the server's own, which is
 * why they are here and not in {@code server}: an application embedding the
 * watcher brings its own, and should depend on that module instead of this one.
 */
@NullMarked
module io.github.ralfspoeth.xldr.app {
    exports io.github.ralfspoeth.xldr.app;
    opens io.github.ralfspoeth.xldr.app to info.picocli;

    requires io.github.ralfspoeth.xldr.server;
    requires io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.xldr.spec;
    requires java.sql;
    requires java.logging;
    requires com.zaxxer.hikari;
    requires info.picocli;
    requires org.slf4j.jul;

    requires static org.jspecify;

    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
}
