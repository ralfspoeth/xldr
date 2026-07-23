// Integration test module: no production code, so the only descriptor is this
// test one. `uses` plus `requires com.pd.xldr.xml` is what lets ServiceLoader
// discover the XML adapter on the module path.
open module com.pd.xldr.it.test {
    requires com.pd.xldr.spec;
    requires com.pd.xldr.ldr;
    requires com.pd.xldr.xml;
    requires java.sql;
    requires org.junit.jupiter.api;

    uses com.pd.xldr.ia.InputAdapterFactory;
}
