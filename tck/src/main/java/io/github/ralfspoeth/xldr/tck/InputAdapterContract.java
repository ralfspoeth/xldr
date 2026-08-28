package io.github.ralfspoeth.xldr.tck;

import io.github.ralfspoeth.xldr.ia.*;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The obligations of an input adapter, as tests. Extend this, say what your
 * factory is and give it something to read, and the contract in {@code ia}'s
 * {@linkplain io.github.ralfspoeth.xldr.ia package documentation} is checked
 * against your implementation.
 * {@snippet :
 * class MtConformanceTest extends InputAdapterContract {
 *     protected InputAdapterFactory factory() { return new MtInputAdapterFactory(); }
 *     protected String mimeType() { return "text/x-swift"; }
 *     protected byte[] sample() { return MESSAGE.getBytes(US_ASCII); }
 *     protected InputSpec spec() { return new InputSpec(mimeType(), List.of, List.of(), Map.of()); } // @replace substring='List.of, List.of(), Map.of()' replacement='...'
 * }
 *}
 *
 * <h2>What it does and does not cover</h2>
 *
 * Six of the ten obligations are checkable without knowing the format, and those
 * are here. The other four are not, and are yours to test: <em>refuse at
 * construction what the spec already proves wrong</em> depends on what your
 * format cannot mean; <em>say what is wrong with the record</em> depends on what
 * a bad record looks like; whether <em>empty differs from absent</em> is a
 * property of the format; and <em>hold no mutable state</em> is only sampled here,
 * by reading twice.
 * <p>
 * A green run therefore says an adapter keeps the obligations that can be stated
 * generically. It does not say the adapter is right, and no kit could.
 *
 * <h2>Why the methods are public</h2>
 *
 * Tests elsewhere in this project are package-private, which is all JUnit needs.
 * Here the subclass is in another module, so the inherited methods have to be
 * reachable across the module boundary; {@code protected} would do for the hooks
 * and {@code public} is what a reflective runner needs for the tests.
 */
public abstract class InputAdapterContract {

    // ---- what the implementation supplies --------------------------------------

    /**
     * The factory under test.
     * <p>
     * Testing your own module, a constructor call is the obvious thing. From
     * anywhere else it may not be available: a format module has no reason to
     * export the package its factory sits in - none of the five shipped with
     * xldr does, the class being public only because {@code provides ... with}
     * requires it - so from outside, {@link InputAdapterFactory#of} and a
     * {@code requires} that puts the module in the graph is the whole interface.
     */
    protected abstract InputAdapterFactory factory();

    /** A MIME type this factory claims. */
    protected abstract String mimeType();

    /**
     * A spec this factory can build an adapter from, reading {@link #sample()}.
     * <p>
     * Its first record selector is the one exercised, and it should declare at
     * least two field selectors so that asking for a subset means something. At
     * least one field with a declared type - a {@code TEMPORAL}, an {@code INTEGRAL},
     * a {@code DECIMAL} - is what makes the typing obligations bite; a spec that
     * is all {@code TEXT} will pass them without having been asked anything.
     */
    protected abstract InputSpec spec();

    /** An input the spec reads, yielding at least one record. */
    protected abstract byte[] sample();

    // ---- what an implementation may adjust -------------------------------------

    /**
     * Whether the format names fields of its own, so that a field selector the
     * record selector did not declare is legitimate rather than a mistake - true
     * for a headed CSV, where a column of the header is an implicit {@code TEXT}
     * field. The default is false, which is the commoner case and the stricter
     * one.
     */
    protected boolean namesItsOwnFields() {
        return false;
    }

    /** A MIME type no adapter should claim. */
    protected String unknownMimeType() {
        return "application/x-nothing-reads-this";
    }

    // ---- the obligations -------------------------------------------------------

    /** It claims the type it says it claims, by either overload. */
    @Test
    public void readsTheMimeTypeItClaims() {
        assertAll(
                () -> assertTrue(factory().reads(mimeType()),
                        () -> "the factory does not claim " + mimeType()),
                () -> assertTrue(factory().reads(spec()),
                        () -> "reads(InputSpec) disagrees with reads(String) for " + mimeType()));
    }

    /** And does not claim everything, which a careless {@code reads} would. */
    @Test
    public void doesNotClaimAmimeTypeItCannotRead() {
        assertFalse(factory().reads(unknownMimeType()),
                () -> "the factory claims " + unknownMimeType() + ", so it claims anything");
    }

    /**
     * Settings are per format and one spec may be read by more than one adapter,
     * so a name this format has no use for is not an error - and must not change
     * what is read either.
     */
    @Test
    public void ignoresApropertyItDoesNotRecognise() throws IOException {
        var properties = new HashMap<>(spec().properties());
        properties.put("x-nothing-uses-this", "whatever");
        var padded = new InputSpec(spec().mimeType(), spec().recordSelectors(), spec().vars(), properties);

        var expected = valuesOf(rowsOf(adapter(spec())));
        var actual = assertDoesNotThrow(() -> valuesOf(rowsOf(adapter(padded))),
                "an unrecognised property was refused");
        assertEquals(expected, actual, "an unrecognised property changed what was read");
    }

    /**
     * A record selector the spec did not declare is a typo in a mapping, and the
     * adapter is the only place it can surface.
     */
    @Test
    public void refusesArecordSelectorTheSpecDoesNotDeclare() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> drain(adapter(spec()).parse(source(), "x-no-such-record-selector", fieldNames())),
                "an undeclared record selector was accepted");
        assertTrue(thrown.getMessage().contains("x-no-such-record-selector"),
                () -> "the message does not name what was asked for: " + thrown.getMessage());
    }

    /**
     * As is a field selector the record selector has not got - unless the format
     * names its own, which {@link #namesItsOwnFields()} declares.
     */
    @Test
    public void refusesAfieldSelectorTheRecordSelectorHasNot() {
        if (namesItsOwnFields()) {
            return;
        }
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> drain(adapter(spec()).parse(source(), recordSelector().name(),
                        Set.of("x-no-such-field-selector"))),
                "an undeclared field selector was accepted");
        assertTrue(thrown.getMessage().contains("x-no-such-field-selector"),
                () -> "the message does not name what was asked for: " + thrown.getMessage());
    }

    /** The result holds one field per requested name, and no others. */
    @Test
    public void declaresTheRequestedFieldsAndNoOthers() throws IOException {
        var wanted = fieldNames();
        var result = adapter(spec()).parse(source(), recordSelector().name(), wanted);
        try (var rows = result.rows()) {
            rows.forEach(_ -> {});
        }
        assertEquals(wanted, result.fields().stream().map(Field::name).collect(Collectors.toSet()));
        assertEquals(wanted.size(), result.fields().size(), "a field is declared more than once");
    }

    /** Asking for fewer yields fewer, rather than everything the spec declares. */
    @Test
    public void exposesOnlyTheSubsetAskedFor() throws IOException {
        var one = Set.of(fieldNames().iterator().next());
        var result = adapter(spec()).parse(source(), recordSelector().name(), one);
        try (var rows = result.rows()) {
            rows.forEach(_ -> {});
        }
        assertEquals(one, result.fields().stream().map(Field::name).collect(Collectors.toSet()));
    }

    /**
     * A field reports the type the spec declared, {@code TEXT} where it declared
     * nothing. This is the obligation an adapter is likeliest to miss: returning
     * text for everything compiles, runs, and hands the loader a {@code String}
     * for a numeric column.
     */
    @Test
    public void everyFieldReportsItsDeclaredType() throws IOException {
        var declared = recordSelector().fieldSelectors()
                .stream()
                .collect(Collectors.toMap(FieldSelectorSpec::name,
                        fs -> (fs.dataType() == null ? DataType.TEXT : fs.dataType()).clazz()));
        var result = adapter(spec()).parse(source(), recordSelector().name(), fieldNames());
        try (var rows = result.rows()) {
            rows.forEach(_ -> {});
        }
        assertAll(result.fields().stream().map(f -> () ->
                assertEquals(declared.get(f.name()), f.type(),
                        () -> "field '" + f.name() + "' reports " + f.type().getSimpleName()
                                + " where the spec declared "
                                + declared.get(f.name()).getSimpleName())));
    }

    /** And the values match: null, or an instance of the field's own type. */
    @Test
    public void everyValueIsOfItsFieldsType() throws IOException {
        var result = adapter(spec()).parse(source(), recordSelector().name(), fieldNames());
        var fields = result.fields();
        var checks = new ArrayList<Executable>();
        try (var rows = result.rows()) {
            int i = 0;
            for (var row : rows.toList()) {
                int at = ++i;
                for (var f : fields) {
                    var value = row.get(f.name());
                    if (value != null) {
                        checks.add(() -> assertInstanceOf(f.type(), value,
                                () -> "record " + at + ", field '" + f.name() + "'"));
                    }
                }
            }
        }
        assertFalse(checks.isEmpty(), "the sample yielded no values at all, so nothing was checked");
        assertAll(checks);
    }

    /**
     * The sample yields records, which every test above depends on - a spec that
     * silently matches nothing would pass them all.
     */
    @Test
    public void theSampleYieldsRecords() throws IOException {
        assertFalse(rowsOf(adapter(spec())).isEmpty(),
                "the spec matched no record in the sample, so this kit checked almost nothing");
    }

    /**
     * One adapter serves every record mapping of a file, so it is asked more than
     * once, with a fresh stream each time. Reading twice must give the same
     * answer - a sample of the no-mutable-state rule, not a proof of it.
     */
    @Test
    public void readsTheSameRowsTwice() throws IOException {
        var adapter = adapter(spec());
        assertEquals(valuesOf(rowsOf(adapter)), valuesOf(rowsOf(adapter)),
                "the second read of the same adapter differed from the first");
    }

    // ---- helpers ---------------------------------------------------------------

    private InputAdapter adapter(InputSpec spec) {
        return factory().createInputAdapter(spec);
    }

    private InputStream source() {
        return new ByteArrayInputStream(sample());
    }

    /** the first record selector of the spec, which is the one exercised */
    private RecordSelectorSpec recordSelector() {
        return spec().recordSelectors().iterator().next();
    }

    private Set<String> fieldNames() {
        return recordSelector().fieldSelectors().stream()
                .map(FieldSelectorSpec::name)
                .collect(Collectors.toSet());
    }

    private List<Row> rowsOf(InputAdapter adapter) throws IOException {
        try (var rows = adapter.parse(source(), recordSelector().name(), fieldNames()).rows()) {
            return rows.toList();
        }
    }

    /** rows as plain maps, so that two reads can be compared */
    private List<Map<String, @Nullable Object>> valuesOf(List<Row> rows) {
        return rows.stream().map(row -> {
            // LinkedHashMap rather than a Map.of: a value may be null, and
            // null is exactly what several of these obligations are about
            Map<String, @Nullable Object> values = new LinkedHashMap<>();
            for (var name : fieldNames()) {
                values.put(name, row.get(name));
            }
            return values;
        }).toList();
    }

    private static void drain(Result result) {
        try (var rows = result.rows()) {
            rows.forEach(_ -> {});
        }
    }
}
