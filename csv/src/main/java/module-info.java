import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

/**
 * The CSV input adapter, for separated-value files with or without a header
 * row, one record per line.
 */
module io.github.ralfspoeth.xldr.csv {
    requires transitive io.github.ralfspoeth.xldr.ia;

    provides InputAdapterFactory
            with io.github.ralfspoeth.xldr.csv.CsvFileHandlerFactory;
}