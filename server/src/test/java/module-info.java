open module io.github.ralfspoeth.xldr.server.test {
    requires io.github.ralfspoeth.xldr.server;
    // FeedRegistry is built on one, and a test drives it directly rather than
    // through a running Watcher
    requires io.github.ralfspoeth.filews;
    // the counters ServerStatus reports, which live in ldr
    requires io.github.ralfspoeth.xldr.ldr;
    requires org.junit.jupiter.api;
}
