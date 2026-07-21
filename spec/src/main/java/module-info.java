import com.pd.xldr.spec.io.JsonMappingSpecReader;
import com.pd.xldr.spec.io.MappingSpecReader;
import com.pd.xldr.spec.io.PropertiesMappingSpecReader;
import com.pd.xldr.spec.io.XmlMappingSpecReader;

module com.pd.xldr.spec {
    exports com.pd.xldr.spec;
    exports com.pd.xldr.spec.io;
    requires io.github.ralfspoeth.greyson;
    requires io.github.ralfspoeth.dirs;
    requires io.github.ralfspoeth.xmls;
    requires java.xml;

    provides MappingSpecReader with JsonMappingSpecReader, PropertiesMappingSpecReader, XmlMappingSpecReader;
}