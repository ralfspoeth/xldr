open module io.github.ralfspoeth.xldr.it.test {
    requires io.github.ralfspoeth.xldr.app;
    requires io.github.ralfspoeth.xldr.spec;
    requires io.github.ralfspoeth.xldr.ldr;
    requires io.github.ralfspoeth.xldr.xml;
    requires io.github.ralfspoeth.xldr.csv;
    requires io.github.ralfspoeth.xldr.flt;
    requires io.github.ralfspoeth.xldr.json;
    requires io.github.ralfspoeth.xldr.xlsx;
    requires java.sql;
    // reading the server's JMX statistics
    requires java.management;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    // driving the validate subcommand through the command line
    requires info.picocli;
    requires org.junit.jupiter.api;

    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
}
