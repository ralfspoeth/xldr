open module io.github.ralfspoeth.xldr.it.test {
    // require the core parts
    requires transitive io.github.ralfspoeth.xldr.it;
    // require the adapters
    requires io.github.ralfspoeth.xldr.xml;
    requires io.github.ralfspoeth.xldr.csv;
    requires io.github.ralfspoeth.xldr.flt;
    requires io.github.ralfspoeth.xldr.json;
    requires io.github.ralfspoeth.xldr.xlsx;
    // testing
    requires org.junit.jupiter.api;
    requires org.apache.poi.ooxml;

    // we certainly use the factory;
    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
    uses java.sql.Driver;
}
