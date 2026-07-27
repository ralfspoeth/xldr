/**
 * The input adapter SPI. An adapter turns one file into records and fields;
 * the application finds one for a MIME type through {@link java.util.ServiceLoader}.
 */
module io.github.ralfspoeth.xldr.ia {
    requires transitive io.github.ralfspoeth.xldr.spec;
    exports io.github.ralfspoeth.xldr.ia;
}