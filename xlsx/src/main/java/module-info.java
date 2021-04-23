import com.pd.xldr.xlsx.ExcelAdapterFactory;

module com.pd.xldr.xlsx {
    requires transitive com.pd.xldr.ia;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    provides com.pd.xldr.ia.InputAdapterFactory
            with ExcelAdapterFactory;

}