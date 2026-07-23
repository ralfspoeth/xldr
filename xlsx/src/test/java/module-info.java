open module com.pd.xldr.xlsx.test {
    uses com.pd.xldr.ia.InputAdapterFactory;
    requires com.pd.xldr.xlsx;
    // the test builds its own .xlsx fixture in memory
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    requires org.junit.jupiter.api;
}
