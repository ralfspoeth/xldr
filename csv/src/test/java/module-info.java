// Test-only module descriptor: `open` so JUnit can reflect into the test
// classes without exporting/opening anything in the production module-info.
// Mirrors the main module's `requires`; `provides` is omitted (tests construct
// the factory directly) and the whole module is opened instead.
open module com.pd.xldr.csv.test {
    uses com.pd.xldr.ia.InputAdapterFactory;
    requires com.pd.xldr.csv;
    requires io.github.ralfspoeth.basix;
    requires org.junit.jupiter.api;
}
