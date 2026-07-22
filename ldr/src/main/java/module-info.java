module com.pd.xldr.ldr {
    exports com.pd.xldr.ldr;
    // both appear in Loader's public signatures: InputAdapter/RecordMappingSpec
    // in loadInput, Connection in the constructor
    requires transitive com.pd.xldr.ia;
    requires transitive java.sql;
}
