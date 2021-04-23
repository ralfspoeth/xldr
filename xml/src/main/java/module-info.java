module com.pd.xldr.xml {
    requires java.xml;
    requires com.pd.xldr.ia;
    provides com.pd.xldr.ia.InputAdapterFactory
            with com.pd.xldr.xml.XmlFileHandlerFactory;
}