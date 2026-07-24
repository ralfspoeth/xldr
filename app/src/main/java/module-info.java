module io.github.ralfspoeth.xldr.app {
    exports io.github.ralfspoeth.xldr.app;
    opens io.github.ralfspoeth.xldr.app to info.picocli;

    requires io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.xldr.ldr;
    requires java.sql;
    requires java.logging;
    requires com.zaxxer.hikari;
    requires io.github.ralfspoeth.filews;
    requires info.picocli;
    requires org.slf4j.jul;

    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
}
