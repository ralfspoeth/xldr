import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.XmlMappingSpecReader;
import org.jspecify.annotations.NullMarked;

/**
 * The mapping specification: what an input looks like, how its records map
 * onto database tables, and the readers for the JSON and XML forms of a spec.
 */
@NullMarked
module io.github.ralfspoeth.xldr.spec {
    exports io.github.ralfspoeth.xldr.spec;
    exports io.github.ralfspoeth.xldr.spec.io;
    requires io.github.ralfspoeth.greyson;
    requires io.github.ralfspoeth.xmls;
    requires java.xml;
    requires static org.jspecify;

    provides MappingSpecReader with JsonMappingSpecReader, XmlMappingSpecReader;
}