package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;
import org.jspecify.annotations.NonNull;

import java.util.Map;

import static io.github.ralfspoeth.xldr.it.Conformance.*;

/**
 * The one adapter that names fields of its own: a column of the header is an
 * implicit {@code TEXT} field, so an undeclared field selector is legitimate here
 * and the kit is told to expect that.
 */
class CsvConformanceIT extends InputAdapterContract {

    @Override
    protected @NonNull InputAdapterFactory factory() {
        return discovered(spec());
    }

    @Override
    protected @NonNull String mimeType() {
        return "text/csv";
    }

    @Override
    protected boolean namesItsOwnFields() {
        return true;
    }

    @Override
    protected @NonNull InputSpec spec() {
        return Conformance.spec(mimeType(), Map.of("fieldSeparator", ","), Locator.every(),
                field("id", "id", DataType.INTEGRAL),
                field("name", "name", DataType.TEXT),
                field("since", "since", DataType.TEMPORAL));
    }

    @Override
    protected byte @NonNull [] sample() {
        return bytes("""
                id,name,since
                1,Alice,2026-03-01T00:00
                2,Bob,2026-03-15T00:00
                """);
    }
}
