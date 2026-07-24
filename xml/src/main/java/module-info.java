module io.github.ralfspoeth.xldr.xml {
    requires java.xml;
    requires transitive io.github.ralfspoeth.xldr.ia;

    provides io.github.ralfspoeth.xldr.ia.InputAdapterFactory
            with io.github.ralfspoeth.xldr.xml.XmlFileHandlerFactory;
}
