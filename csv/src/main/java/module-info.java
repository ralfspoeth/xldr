import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

module io.github.ralfspoeth.xldr.csv {
    requires transitive io.github.ralfspoeth.xldr.ia;

    provides InputAdapterFactory
            with io.github.ralfspoeth.xldr.csv.CsvFileHandlerFactory;
}