package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;
import org.jspecify.annotations.NonNull;

import java.util.Map;

import static io.github.ralfspoeth.xldr.it.Conformance.*;

class XmlConformanceIT extends InputAdapterContract {

    @Override
    protected @NonNull InputAdapterFactory factory() {
        return discovered(spec());
    }

    @Override
    protected @NonNull String mimeType() {
        return "text/xml";
    }

    @Override
    protected @NonNull InputSpec spec() {
        return Conformance.spec(mimeType(), Map.of(), Locator.at("/rows/row"),
                field("id", "@id", DataType.INTEGRAL),
                field("name", "name", DataType.TEXT),
                field("amount", "amount", DataType.DECIMAL));
    }

    @Override
    protected byte @NonNull [] sample() {
        return bytes("""
                <?xml version="1.0" encoding="UTF-8"?>
                <rows>
                    <row id="1"><name>Alice</name><amount>12.50</amount></row>
                    <row id="2"><name>Bob</name><amount>98.00</amount></row>
                </rows>
                """);
    }
}
