import com.pd.xldr.ia.*;

module com.pd.xldr.csv {
    requires com.pd.xldr.ia;
    provides InputAdapterFactory
            with com.pd.xldr.csv.CsvFileHandlerFactory;
}