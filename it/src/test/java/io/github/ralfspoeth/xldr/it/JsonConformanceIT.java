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

class JsonConformanceIT extends InputAdapterContract {

    @Override
    protected @NonNull InputAdapterFactory factory() {
        return discovered(spec());
    }

    @Override
    protected @NonNull String mimeType() {
        return "application/json";
    }

    @Override
    protected @NonNull InputSpec spec() {
        return Conformance.spec(mimeType(), Map.of(), Locator.at("rows"),
                field("id", "id", DataType.INTEGRAL),
                field("name", "name", DataType.TEXT),
                field("amount", "amount", DataType.DECIMAL));
    }

    @Override
    protected byte @NonNull [] sample() {
        return bytes("""
                { "rows": [
                    { "id": 1, "name": "Alice", "amount": 12.50 },
                    { "id": 2, "name": "Bob",   "amount": 98.00 }
                ] }
                """);
    }

    /**
     * The pointer syntax here is close enough to RFC 6901 to be mistaken for it,
     * so a leading slash is refused with the difference spelled out rather than
     * read as a member whose name happens to begin with one.
     */
    @Override
    protected @NonNull List<Refusal> refusals() {
        return List.of(
                new Refusal("a pointer in RFC 6901's syntax rather than this one",
                        Conformance.spec(mimeType(), Map.of(), Locator.at("/rows"),
                                field("id", "id", DataType.INTEGRAL))),
                new Refusal("two record selectors of one name",
                        twice(mimeType(), records(Locator.at("rows"),
                                field("id", "id", DataType.INTEGRAL)))));
    }

    /**
     * Once a document is a tree it has no line numbers, so the ordinal is the
     * whole of what identifies a record here - which is why the adapter counts
     * the elements as it hands them out rather than working it out afterwards.
     */
    @Override
    protected @NonNull List<Breakage> breakages() {
        return List.of(new Breakage("a string where the spec declared a decimal",
                bytes("""
                        { "rows": [
                            { "id": 1, "name": "Alice", "amount": 12.50 },
                            { "id": 2, "name": "Bob",   "amount": "not a number" }
                        ] }
                        """),
                "record 2"));
    }
}
