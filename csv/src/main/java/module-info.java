import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import org.jspecify.annotations.NullMarked;

/**
 * The CSV input adapter, for separated-value files with or without a header
 * row. A record is a line, unless a quoted field carries a line break.
 */
@NullMarked
module io.github.ralfspoeth.xldr.csv {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires static org.jspecify;

    provides InputAdapterFactory
            with io.github.ralfspoeth.xldr.csv.CsvFileHandlerFactory;
}