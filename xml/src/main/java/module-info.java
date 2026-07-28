import org.jspecify.annotations.NullMarked;

/**
 * The XML input adapter, selecting records and fields with XPath.
 */
@NullMarked
module io.github.ralfspoeth.xldr.xml {
    requires java.xml;
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires static org.jspecify;

    provides io.github.ralfspoeth.xldr.ia.InputAdapterFactory
            with io.github.ralfspoeth.xldr.xml.XmlFileHandlerFactory;
}
