/**
 * The server as a library: watches the configured roots and loads the files that
 * appear in the feeds below them.
 * <p>
 * Everything here is about the watching and the loading. Nothing here parses a
 * command line, builds a connection pool or configures logging - those are the
 * decisions of whoever runs the server, and they live in {@code app}, which is
 * one such runner. An application embedding this module supplies its own
 * {@link io.github.ralfspoeth.xldr.server.ConnectionSource} and drives a
 * {@link io.github.ralfspoeth.xldr.server.Watcher} directly.
 * <p>
 * Input adapters are found through {@link java.util.ServiceLoader}, so which
 * formats a deployment reads is decided by its module path rather than by any
 * code here.
 */
module io.github.ralfspoeth.xldr.server {
    exports io.github.ralfspoeth.xldr.server;

    requires io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.xldr.ldr;
    requires java.sql;
    requires java.logging;
    // the JMX statistics, which need no agent and no dependency
    requires java.management;
    requires io.github.ralfspoeth.filews;

    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
}
