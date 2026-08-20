open module io.github.ralfspoeth.xldr.spec.test {
    requires io.github.ralfspoeth.xldr.spec;
    // validating a spec against the published XSD
    requires java.xml;
    // and against the published JSON schema, which carries the two
    // exactly-one-of rules XSD 1.0 cannot express and so is the stricter of the
    // two - and until now the one nothing checked
    requires com.networknt.schema;
    requires org.junit.jupiter.api;
}
