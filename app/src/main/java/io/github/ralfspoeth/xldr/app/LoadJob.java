package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * Loads one file according to one {@link MappingSpec}.
 * <p>
 * The sequence is: pick the input adapter factory that accepts the input spec's
 * MIME type, create a single adapter for the file, and then run every record
 * mapping against it - each with a freshly opened stream, since a stream is
 * read only once.
 * <p>
 * The whole file is one transaction: {@link Loader#close()} commits, or rolls
 * back if any mapping failed.
 */
public class LoadJob {

    private final MappingSpec mappingSpec;
    private final ConnectionSource connectionSource;
    private final Properties adapterProperties;

    /**
     * @param adapterProperties format specific settings handed to the input
     *                          adapter factory, e.g. {@code fieldSeparator} for CSV
     */
    public LoadJob(MappingSpec mappingSpec, ConnectionSource connectionSource, Properties adapterProperties) {
        this.mappingSpec = mappingSpec;
        this.connectionSource = connectionSource;
        this.adapterProperties = adapterProperties;
    }

    /**
     * @return the total number of rows inserted across all record mappings
     */
    public int load(Path file) throws IOException, SQLException {
        var adapter = createInputAdapter(mappingSpec.inputSpec());

        try (var connection = connectionSource.getConnection();
             var loader = new Loader(mappingSpec, connection)) {
            int total = 0;
            for (var mapping : mappingSpec.recordMappingSpecs()) {
                try (var in = Files.newInputStream(file)) {
                    total += loader.loadInput(adapter, in, mapping);
                }
            }
            return total;
        }
    }

    /**
     * One adapter serves every record mapping of the file - {@code parse} takes
     * the record selector as a parameter, so there is no reason to rebuild it
     * (and, for XML, recompile every XPath) per mapping.
     */
    private InputAdapter createInputAdapter(InputSpec inputSpec) {
        var factory = ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(iaf -> iaf.reads(inputSpec))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no input adapter for mime type " + inputSpec.mimeType()));
        factory.setProperties(adapterProperties);
        return factory.createInputAdapter(inputSpec);
    }
}
