module com.pd.xldr.app {
    requires com.pd.xldr.ia;
    requires com.pd.xldr.ldr;
    requires com.pd.xldr.spec;
    requires java.sql;
    // JNDI: the app resolves the data source name carried by the output spec
    requires java.naming;

    uses com.pd.xldr.ia.InputAdapterFactory;
}
