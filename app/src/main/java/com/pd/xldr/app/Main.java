package com.pd.xldr.app;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.logging.LogManager;

import static java.lang.System.Logger.Level.INFO;

/**
 * Entry point: starts the server and watches the configured roots until the
 * process is asked to stop.
 */
@Command(
        name = "xldr",
        mixinStandardHelpOptions = true,
        version = "xldr " + Main.VERSION,
        description = "Watches the configured roots and loads files that appear into the target database."
)
public class Main implements Callable<Integer> {

    static final String VERSION = "1.0";

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    @Parameters(
            index = "0",
            paramLabel = "CONFIG",
            description = "the server configuration properties file (xldr.roots, jdbc.url, ...)"
    )
    private Path configFile;

    static void main(String[] args) {
        initLogging();
        // picocli turns a parse error into usage help and exit code 2, an
        // exception from call() into a stack trace and exit code 1, and the
        // value call() returns into the exit code otherwise
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        var config = AppConfig.load(configFile);
        // both belong to the process: the pool is opened once and closed on exit
        try (var pool = new ConnectionPool(config);
             var watcher = new Watcher(config, pool)) {
            watcher.start();
            awaitShutdown();
            LOG.log(INFO, "shutting down");
        }
        return 0;
    }

    /**
     * Blocks until the JVM is asked to terminate, so that the loads in flight
     * finish inside the try-with-resources above rather than being cut off.
     */
    private static void awaitShutdown() throws InterruptedException {
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "xldr-shutdown"));
        stopped.await();
    }

    /**
     * Applies the bundled {@code logging.properties} unless the deployment
     * already points java.util.logging at a configuration of its own. slf4j
     * itself needs no setup - {@code slf4j-jdk14} is discovered as a service
     * provider - so configuring JUL configures everything.
     */
    private static void initLogging() {
        if (System.getProperty("java.util.logging.config.file") != null
                || System.getProperty("java.util.logging.config.class") != null) {
            return;
        }
        try (var in = Main.class.getResourceAsStream("/logging.properties")) {
            if (in != null) {
                LogManager.getLogManager().readConfiguration(in);
            }
        } catch (IOException e) {
            System.err.println("could not apply the bundled logging configuration: " + e);
        }
    }
}
