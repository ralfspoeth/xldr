open module com.pd.xldr.it.test {
    requires com.pd.xldr.app;
    requires com.pd.xldr.spec;
    requires com.pd.xldr.ldr;
    requires com.pd.xldr.xml;
    requires com.pd.xldr.csv;
    requires java.sql;
    requires org.junit.jupiter.api;

    uses com.pd.xldr.ia.InputAdapterFactory;
}
