package com.pd.xldr.app;

import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.io.JsonMappingSpecReader;
import com.pd.xldr.spec.io.MappingSpecReader;
import com.pd.xldr.spec.io.PropertiesMappingSpecReader;
import com.pd.xldr.spec.io.XmlMappingSpecReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.LogManager;

public class Main {

    static void main(String[] args) throws Exception {
        initLogging();
        if (args.length != 3) {
            System.err.println("usage: xldr <config.properties> <mapping-spec-file> <input-file>");
            System.exit(2);
            return;
        }
        var config = AppConfig.load(Path.of(args[0]));
        var mappingSpec = readMappingSpec(Path.of(args[1]));
        var input = Path.of(args[2]);
        // the pool belongs to the process, not to a single load - once the
        // server watches directories it is opened at startup and closed on exit
        try (var pool = new ConnectionPool(config)) {
            var rows = new LoadJob(mappingSpec, pool).load(input);
            System.out.printf("inserted %d row(s) from %s%n", rows, input);
        }
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

    static MappingSpec readMappingSpec(Path file) throws IOException {
        var reader = readerFor(file);
        try (var in = Files.newBufferedReader(file)) {
            return reader.readFrom(in);
        }
    }

    /**
     * Picks the reader by file extension.
     * <p>
     * {@code MappingSpecReader} carries no discriminator - no
     * {@code accepts(...)} the way {@code InputAdapterFactory} has one - so the
     * implementations cannot be told apart through {@code ServiceLoader} even
     * though {@code spec} declares them as providers. Hence the explicit switch.
     */
    private static MappingSpecReader readerFor(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return new JsonMappingSpecReader();
        } else if (name.endsWith(".xml")) {
            return new XmlMappingSpecReader();
        } else if (name.endsWith(".properties")) {
            return new PropertiesMappingSpecReader();
        } else {
            throw new IllegalArgumentException("unsupported mapping spec format: " + file);
        }
    }
}
