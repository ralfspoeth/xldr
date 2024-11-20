module com.pd.xldr.spec {
    exports com.pd.xldr.spec;
    exports com.pd.xldr.spec.io;
    requires io.github.ralfspoeth.json;
    requires io.github.ralfspoeth.dirs;

    uses MappingSpecReader;
    provides MappingSpecReader with JsonMappingSpecReader, PropertiesMappingSpecReader;
}