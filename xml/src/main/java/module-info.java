module com.pd.xldr.xml {
    requires java.xml;
    // transitive so that a consumer requiring this module also sees the
    // InputAdapter API it is handed back
    requires transitive com.pd.xldr.ia;

    provides com.pd.xldr.ia.InputAdapterFactory
            with com.pd.xldr.xml.XmlFileHandlerFactory;
}
