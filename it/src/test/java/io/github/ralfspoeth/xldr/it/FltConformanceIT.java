package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;
import org.jspecify.annotations.NonNull;

import java.util.Map;

import static io.github.ralfspoeth.xldr.it.Conformance.*;

class FltConformanceIT extends InputAdapterContract {

    @Override
    protected @NonNull InputAdapterFactory factory() {
        return discovered(spec());
    }

    @Override
    protected @NonNull String mimeType() {
        return "text/plain";
    }

    @Override
    protected @NonNull InputSpec spec() {
        return Conformance.spec(mimeType(), Map.of(), Locator.every(),
                field("id", "0:3", DataType.INTEGRAL),
                field("name", "3:8", DataType.TEXT),
                field("amount", "8:14", DataType.DECIMAL));
    }

    @Override
    protected byte @NonNull [] sample() {
        return bytes("""
                001Alice012.50
                002Bob  098.00
                """);
    }
}
