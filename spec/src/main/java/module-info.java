import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.XmlMappingSpecReader;

module io.github.ralfspoeth.xldr.spec {
    exports io.github.ralfspoeth.xldr.spec;
    exports io.github.ralfspoeth.xldr.spec.io;
    requires io.github.ralfspoeth.greyson;
    requires io.github.ralfspoeth.xmls;
    requires java.xml;

    provides MappingSpecReader with JsonMappingSpecReader, XmlMappingSpecReader;
}