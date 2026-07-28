import io.github.ralfspoeth.xldr.xlsx.ExcelAdapterFactory;
import org.jspecify.annotations.NullMarked;

/**
 * The Excel input adapter, reading both {@code .xls} and {@code .xlsx} through
 * Apache POI.
 */
@NullMarked
module io.github.ralfspoeth.xldr.xlsx {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    requires org.slf4j.jul;
    requires static org.jspecify;
    provides io.github.ralfspoeth.xldr.ia.InputAdapterFactory
            with ExcelAdapterFactory;

}