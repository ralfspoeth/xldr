package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;
import org.jspecify.annotations.NonNull;

import java.util.List;
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

    /**
     * Here what a spec proves wrong is mostly a setting rather than a selector:
     * this format has more knobs than the other four together, and a knob turned
     * to something it does not mean is exactly the kind of mistake that would
     * otherwise read as a file full of nulls.
     */
    @Override
    protected @NonNull List<Refusal> refusals() {
        return List.of(
                new Refusal("a header setting that is neither present nor absent - and would"
                        + " otherwise read as absent, loading the names row as data",
                        Conformance.spec(mimeType(), Map.of("header", "yes"), Locator.every(),
                                field("id", "id", DataType.INTEGRAL))),
                new Refusal("an emptyLine setting that names neither skip nor stop",
                        Conformance.spec(mimeType(), Map.of("emptyLine", "maybe"), Locator.every(),
                                field("id", "id", DataType.INTEGRAL))),
                new Refusal("a quote of more than one character",
                        Conformance.spec(mimeType(), Map.of("quote", "''"), Locator.every(),
                                field("id", "id", DataType.INTEGRAL))),
                new Refusal("names taken from a header the spec says is not there",
                        Conformance.spec(mimeType(),
                                Map.of("header", "absent", "fieldsFromHeader", "true"),
                                Locator.every(), field("id", "1", DataType.INTEGRAL))),
                new Refusal("two record selectors of one name",
                        twice(mimeType(), records(Locator.every(),
                                field("id", "id", DataType.INTEGRAL)))));
    }

    /**
     * A ragged line: the header declares three columns and these rows carry two,
     * which is a short record rather than a broken one.
     */
    @Override
    protected @NonNull List<Absence> absences() {
        return List.of(new Absence("a line with no third field, where the header has one",
                bytes("""
                        id,name,since
                        3,Carol
                        4,Dave
                        """),
                "since"));
    }

    /**
     * A quote that opens a field and never closes it swallows the rest of the
     * file, so the complaint has to name the line that opened it rather than the
     * end of the file, where the trouble only becomes apparent. Line 2 is the
     * first line after the header.
     */
    @Override
    protected @NonNull List<Breakage> breakages() {
        return List.of(new Breakage("a quoted field left open to the end of the file",
                bytes("""
                        id,name,since
                        3,"Carol
                        """),
                "line 2"));
    }
}
