package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;

import java.util.Map;

import static io.github.ralfspoeth.xldr.it.Conformance.*;

class JsonConformanceIT extends InputAdapterContract {

    @Override
    protected InputAdapterFactory factory() {
        return discovered(spec());
    }

    @Override
    protected String mimeType() {
        return "application/json";
    }

    @Override
    protected InputSpec spec() {
        return Conformance.spec(mimeType(), Map.of(), new Locator.At("rows"),
                field("id", "id", DataType.INTEGRAL),
                field("name", "name", DataType.TEXT),
                field("amount", "amount", DataType.DECIMAL));
    }

    @Override
    protected byte[] sample() {
        return bytes("""
                { "rows": [
                    { "id": 1, "name": "Alice", "amount": 12.50 },
                    { "id": 2, "name": "Bob",   "amount": 98.00 }
                ] }
                """);
    }
}
