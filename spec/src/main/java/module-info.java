import com.pd.xldr.spec.io.JsonMappingSpecReader;
import com.pd.xldr.spec.io.MappingSpecReader;
import com.pd.xldr.spec.io.PropertiesMappingSpecReader;

module com.pd.xldr.spec {
    exports com.pd.xldr.spec;
    exports com.pd.xldr.spec.io;
    requires io.github.ralfspoeth.json;
    uses MappingSpecReader;
    provides MappingSpecReader with JsonMappingSpecReader, PropertiesMappingSpecReader;
}