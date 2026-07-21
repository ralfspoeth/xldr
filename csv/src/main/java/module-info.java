import com.pd.xldr.ia.InputAdapterFactory;

module com.pd.xldr.csv {
    requires transitive com.pd.xldr.ia;
    requires io.github.ralfspoeth.basix;

    provides InputAdapterFactory
            with com.pd.xldr.csv.CsvFileHandlerFactory;
}