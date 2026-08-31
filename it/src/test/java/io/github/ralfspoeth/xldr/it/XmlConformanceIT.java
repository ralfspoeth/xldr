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

    /**
     * A document has records to point at, so the two locators that do not point
     * are the spec having been written for another format - and an XPath that
     * will not compile is caught when the adapter is built rather than on the
     * first record of the first file.
     */
    @Override
    protected @NonNull List<Refusal> refusals() {
        return List.of(
                new Refusal("no locator, in a format where a record has to be pointed at",
                        Conformance.spec(mimeType(), Map.of(), Locator.every(),
                                field("id", "@id", DataType.INTEGRAL))),
                new Refusal("a field selector that is not an XPath expression",
                        Conformance.spec(mimeType(), Map.of(), Locator.at("/rows/row"),
                                field("id", "[[", DataType.TEXT))),
                new Refusal("two record selectors of one name",
                        twice(mimeType(), records(Locator.at("/rows/row"),
                                field("id", "@id", DataType.INTEGRAL)))));
    }

    /**
     * A missing element, on a field the spec typed. The type is what makes this
     * legible: XPath cannot tell an element that is absent from one that is there
     * and empty, both evaluating to the empty string, so a {@code TEXT} field
     * would read {@code ""} either way - which this adapter documents and keeps.
     * A {@code DECIMAL} goes through the shared formats, and an empty string is
     * no number, so the absence survives as {@code null}.
     */
    @Override
    protected @NonNull List<Absence> absences() {
        return List.of(new Absence("a row with no amount element at all",
                bytes("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rows>
                            <row id="3"><name>Carol</name></row>
                            <row id="4"><name>Dave</name></row>
                        </rows>
                        """),
                "amount"));
    }

    /**
     * The field and the expression were already in the complaint; which of the
     * matched nodes it was is the part only the handler knows, a {@code Node}
     * carrying no notion of its position in the node set it came from.
     */
    @Override
    protected @NonNull List<Breakage> breakages() {
        return List.of(new Breakage("an element holding text where the spec declared a decimal",
                bytes("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rows>
                            <row id="1"><name>Alice</name><amount>12.50</amount></row>
                            <row id="2"><name>Bob</name><amount>lots</amount></row>
                        </rows>
                        """),
                "record 2"));
    }
}
