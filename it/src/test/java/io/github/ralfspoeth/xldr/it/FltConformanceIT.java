package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.Discriminator;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.spec.Selector;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;
import org.jspecify.annotations.NonNull;

import java.util.List;
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

    /**
     * The format with the most to refuse, because it is the one that can point at
     * nothing: a fixed-length record is a stretch of characters at declared
     * offsets, so both of the other two locators and both counting selectors are
     * asking it for something it does not have.
     */
    @Override
    protected @NonNull List<Refusal> refusals() {
        return List.of(
                new Refusal("a locator pointing at records, in a file with nowhere to point",
                        Conformance.spec(mimeType(), Map.of(), Locator.at("0:3"),
                                field("id", "0:3", DataType.INTEGRAL))),
                new Refusal("a discriminator counting components, in a record made of offsets",
                        Conformance.spec(mimeType(), Map.of(),
                                Locator.where(new Discriminator.Equals(Selector.nth(1), "A")),
                                field("id", "0:3", DataType.INTEGRAL))),
                new Refusal("a discriminator range with its left bound left out, having no"
                        + " previous field to continue from",
                        Conformance.spec(mimeType(), Map.of(),
                                Locator.where(new Discriminator.Equals(Selector.text(":2"), "00")),
                                field("id", "0:3", DataType.INTEGRAL))),
                new Refusal("a field selector that is not a character range",
                        Conformance.spec(mimeType(), Map.of(), Locator.every(),
                                field("id", "the third one", DataType.TEXT))),
                new Refusal("two record selectors of one name",
                        twice(mimeType(), records(Locator.every(),
                                field("id", "0:3", DataType.INTEGRAL)))),
                new Refusal("nothing declared to read at all", nothing(mimeType())));
    }

    /**
     * A line that stops short. The layout says the amount sits at 8:14 and these
     * lines are seven characters long, which is a fact about the line rather than
     * about the layout - so it reads as an absent value rather than as an error.
     */
    @Override
    protected @NonNull List<Absence> absences() {
        return List.of(new Absence("a line that ends before the amount column begins",
                bytes("""
                        003Carl
                        004Dina
                        """),
                "amount"));
    }

    /**
     * A fixed-length file has no keys, no tags and nothing to quote back, so the
     * line is the whole of what identifies a record in it.
     */
    @Override
    protected @NonNull List<Breakage> breakages() {
        return List.of(new Breakage("letters in the range the layout reads as a number",
                bytes("""
                        001Alice012.50
                        XXXBob  098.00
                        """),
                "line 2"));
    }
}
