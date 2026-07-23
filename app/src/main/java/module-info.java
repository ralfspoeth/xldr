module com.pd.xldr.app {
    exports com.pd.xldr.app;
    opens com.pd.xldr.app to info.picocli;

    requires com.pd.xldr.ia;
    requires com.pd.xldr.ldr;
    requires com.pd.xldr.spec;
    requires java.sql;
    requires java.logging;
    requires com.zaxxer.hikari;
    requires io.github.ralfspoeth.filews;
    requires info.picocli;
    requires org.slf4j.jul;

    uses com.pd.xldr.ia.InputAdapterFactory;
}
