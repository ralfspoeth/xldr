import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.XmlMappingSpecReader;

/**
 * The mapping specification: what an input looks like, how its records map
 * onto database tables, and the readers for the JSON and XML forms of a spec.
 */
module io.github.ralfspoeth.xldr.spec {
    exports io.github.ralfspoeth.xldr.spec;
    exports io.github.ralfspoeth.xldr.spec.io;
    requires io.github.ralfspoeth.greyson;
    requires io.github.ralfspoeth.xmls;
    requires java.xml;

    provides MappingSpecReader with JsonMappingSpecReader, XmlMappingSpecReader;
}