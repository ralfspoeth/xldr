module com.pd.xldr.csv {
    requires com.pd.xldr.ia;
    provides com.pd.xldr.ia.InputAdapterFactory
            with com.pd.xldr.csv.CsvFileHandlerFactory;
}