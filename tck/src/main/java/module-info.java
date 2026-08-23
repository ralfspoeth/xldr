import org.jspecify.annotations.NullMarked;

/**
 * The conformance kit for input adapters: the obligations in {@code ia}'s package
 * documentation, as tests an implementation can be run against.
 * <p>
 * Both requires are transitive, because an implementer extends
 * {@code InputAdapterContract} and therefore needs the SPI types in its own
 * signatures and JUnit's annotations on the methods it inherits. One
 * {@code requires io.github.ralfspoeth.xldr.tck} in a test module is meant to be
 * the whole of the setup.
 */
@NullMarked
module io.github.ralfspoeth.xldr.tck {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires transitive org.junit.jupiter.api;
    requires static org.jspecify;

    exports io.github.ralfspoeth.xldr.tck;
}
