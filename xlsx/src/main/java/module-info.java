import io.github.ralfspoeth.xldr.xlsx.ExcelAdapterFactory;

/**
 * The Excel input adapter, reading both {@code .xls} and {@code .xlsx} through
 * Apache POI.
 */
module io.github.ralfspoeth.xldr.xlsx {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    requires org.slf4j.jul;
    provides io.github.ralfspoeth.xldr.ia.InputAdapterFactory
            with ExcelAdapterFactory;

}