package com.pd.xldr.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.LogManager;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

/**
 * Entry point: starts the server and watches the configured roots until the
 * process is asked to stop.
 */
public class Main {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    static void main(String[] args) throws Exception {
        initLogging();
        if (args.length != 1) {
            System.err.println("usage: xldr <config.properties>");
            System.exit(2);
            return;
        }
        var config = AppConfig.load(Path.of(args[0]));

        // both belong to the process: the pool is opened once and closed on exit
        try (var pool = new ConnectionPool(config);
             var watcher = new Watcher(config, pool)) {
            watcher.start();
            awaitShutdown();
            LOG.log(INFO, "shutting down");
        } catch (Exception e) {
            LOG.log(ERROR, "startup failed", e);
            System.exit(1);
        }
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
