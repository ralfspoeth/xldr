/**
 * Open, because JUnit reflects on the tests.
 * <p>
 * No adapters here. They were required for {@code ValidateTest}, which asked one
 * about a spec; what is left starts the command far enough to be refused by it,
 * which needs no format at all.
 */
open module io.github.ralfspoeth.xldr.app.test {
    requires io.github.ralfspoeth.xldr.app;
    requires info.picocli;
    requires org.junit.jupiter.api;
}
