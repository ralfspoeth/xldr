open module com.pd.xldr.app.test {
    requires com.pd.xldr.app;
    // the CSV input adapter is found through ServiceLoader, and a provider
    // module is only resolved when something requires it - the application
    // module itself deliberately does not, so that adapters stay pluggable
    requires com.pd.xldr.csv;
    requires java.sql;
    requires org.junit.jupiter.api;
}
