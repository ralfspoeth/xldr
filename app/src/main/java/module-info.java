module com.pd.xldr.app {
    exports com.pd.xldr.app;
    // picocli injects values by reflecting into the @Command class's private
    // fields; exporting is not enough, the package has to be open to it
    opens com.pd.xldr.app to info.picocli;

    requires com.pd.xldr.ia;
    requires com.pd.xldr.ldr;
    requires com.pd.xldr.spec;
    requires java.sql;
    requires java.logging;
    requires com.zaxxer.hikari;
    requires io.github.ralfspoeth.filews;
    requires info.picocli;
    // the slf4j -> java.util.logging provider. A service provider module is only
    // resolved if something requires it (or --add-modules names it); as the
    // application module this is where the binding is chosen.
    requires org.slf4j.jul;

    // drivers are resolved by Hikari/DriverManager, not by this module
    uses com.pd.xldr.ia.InputAdapterFactory;
}
