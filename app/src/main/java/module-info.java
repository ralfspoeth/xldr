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
    // the check subcommand builds an adapter and reads a sample through it,
    // which the server does for itself and never on this module's behalf.
    // ia requires spec transitively, so the spec types come with it
    requires io.github.ralfspoeth.xldr.ia;
    // and it asks the loader what it would refuse, without loading anything:
    // Loader.refuseUnknownFunctions and refuseUnusableTarget are both offered
    // for a front end to ask once, when a spec is read, rather than once per
    // load. server requires ldr too but not transitively, and rightly - an
    // embedder drives a Watcher and never names a Loader - so this module says
    // so for itself
    requires io.github.ralfspoeth.xldr.ldr;
    requires java.sql;
    requires java.logging;
    requires com.zaxxer.hikari;
    requires info.picocli;
    requires org.slf4j.jul;

    requires static org.jspecify;
}
