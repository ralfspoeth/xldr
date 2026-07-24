open module io.github.ralfspoeth.xldr.it.test {
    requires io.github.ralfspoeth.xldr.app;
    requires io.github.ralfspoeth.xldr.spec;
    requires io.github.ralfspoeth.xldr.ldr;
    requires io.github.ralfspoeth.xldr.xml;
    requires io.github.ralfspoeth.xldr.csv;
    requires java.sql;
    requires org.junit.jupiter.api;

    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
}
