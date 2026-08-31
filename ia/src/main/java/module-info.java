import org.jspecify.annotations.NullMarked;

/**
 * The input adapter SPI. An adapter turns one file into records and fields;
 * the application finds one for a MIME type through {@link java.util.ServiceLoader}.
 */
@NullMarked
module io.github.ralfspoeth.xldr.ia {
    requires transitive io.github.ralfspoeth.xldr.spec;
    requires static org.jspecify;
    exports io.github.ralfspoeth.xldr.ia;

    // the lookup in InputAdapterFactory.of runs here, so the `uses` is this
    // module's to declare - a caller only needs the adapters on the path
    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
}