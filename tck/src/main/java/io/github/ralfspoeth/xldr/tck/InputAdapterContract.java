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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
 *     protected List<Refusal> refusals() {
 *         return List.of(new Refusal("a tag that is not a tag", specSaying("99z")));
 *     }
 * }
 *}
 *
 * <h2>What it does and does not cover</h2>
 *
 * All ten obligations are here, but not all in the same way. Seven are checkable
 * knowing nothing about the format and are simply run. Three need something only
 * you can produce - a spec your format cannot mean, a record with a value
 * missing, a record that is broken - so for those the kit supplies the checking
 * and asks you for the evidence: {@link #refusals()}, {@link #absences()} and
 * {@link #breakages()}.
 * <p>
 * {@code refusals()} is abstract on purpose. The others default to empty and
 * skip, because a format may honestly have no way to express the case; there is
 * no format that can express nothing wrong, and an author who has not thought
 * about what their adapter refuses is the author whose adapter refuses nothing.
 * That was not a hypothetical: an adapter written against this SPI accepted two
 * selector syntaxes it had never implemented and returned nulls for them, all
 * the way into the database.
 * <p>
 * A green run says an adapter keeps the obligations that can be stated
 * generically, and keeps the other three on the evidence you supplied. It does
 * not say the adapter is right, and no kit could. Where a run skips, read the
 * skip: it names an obligation nothing checked.
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

    // ---- the evidence only the implementation can produce -----------------------

    /**
     * A spec this adapter must refuse to be built from, and why.
     * <p>
     * These carry a {@code byte[]} where they carry a sample, which for a record
     * means {@code equals} compares references. Nothing here compares one; they
     * are argument carriers, named so that a failure says which case did not
     * happen rather than pointing at a lambda.
     *
     * @param because what is wrong with the spec, in the words a failure should
     *                use - "an nth selector over a format with nothing to count"
     * @param spec    the spec itself, otherwise buildable
     */
    public record Refusal(String because, InputSpec spec) {}

    /**
     * A sample in which a declared field has no value, and the field's name.
     *
     * @param because  what is missing and how - "a line that stops before the
     *                 amount column"
     * @param sample   an input the {@link #spec()} still reads, yielding at least
     *                 one record
     * @param field    a field the record selector declares, absent from every
     *                 record of this sample
     */
    public record Absence(String because, byte[] sample, String field) {}

    /**
     * A sample holding a record this adapter cannot read, and the text its
     * complaint must contain.
     *
     * @param because    what is broken - "a quoted field never closed"
     * @param sample     an input that fails, at construction or mid-stream
     * @param identifies the text that says <em>which</em> record it was: a line
     *                   number, a key, a tag. The obligation is to name the
     *                   record, and a message that only says something went wrong
     *                   sends a person to a file of a million lines
     */
    public record Breakage(String because, byte[] sample, String identifies) {}

    /**
     * Specs this adapter refuses at {@code createInputAdapter}, which is the last
     * moment before a file exists.
     * <p>
     * Abstract rather than defaulted, because the answer is the point. Return
     * {@link List#of()} where your format truly proves nothing wrong ahead of
     * time and the check will skip, saying so - which is a different thing from
     * never having been asked.
     */
    protected abstract List<Refusal> refusals();

    /**
     * Samples with a value missing, for the rule that an absent value is
     * {@code null}.
     * <p>
     * Empty by default and then skipped: a format may have no way to express a
     * missing value at all - a fixed-length record always has every column, even
     * if some are blank - and a kit that demanded one would be asking for a
     * fiction. Where the format <em>can</em> tell empty from absent, this is
     * where the difference is stated.
     */
    protected List<Absence> absences() {
        return List.of();
    }

    /**
     * Samples that fail, for the rule that a failure names the record it happened
     * at.
     * <p>
     * Empty by default and then skipped, on the same terms as {@link #absences()}
     * - though a format with no unreadable input is rarer than one with no absent
     * value.
     */
    protected List<Breakage> breakages() {
        return List.of();
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

    // ---- the three that need evidence ------------------------------------------

    /**
     * A spec the format cannot mean is refused when the adapter is built, not at
     * four in the morning halfway through a load.
     */
    @Test
    public void refusesAtConstructionWhatTheSpecProvesWrong() {
        var refusals = refusals();
        assumeFalse(refusals.isEmpty(),
                "this adapter declares no spec it refuses ahead of time, so obligation 1 went unchecked");
        assertAll(refusals.stream().map(refusal -> () ->
                assertThrows(IllegalArgumentException.class,
                        () -> factory().createInputAdapter(refusal.spec()),
                        () -> "built an adapter from a spec it should have refused: " + refusal.because())));
    }

    /**
     * A value that is not there reads as {@code null}, so the loader binds SQL
     * NULL rather than the empty string or a zero.
     */
    @Test
    public void anAbsentValueIsNull() {
        var absences = absences();
        assumeFalse(absences.isEmpty(),
                "this adapter supplied no sample with a value missing, so obligation 6 went unchecked");
        assertAll(absences.stream().map(absence -> () -> {
            assertTrue(fieldNames().contains(absence.field()),
                    () -> "'" + absence.field() + "' is not a field the spec declares, so this"
                            + " checked nothing: " + absence.because());
            var rows = rowsOf(adapter(spec()), absence.sample());
            assertFalse(rows.isEmpty(),
                    () -> "the sample yielded no record, so nothing was absent in it: " + absence.because());
            assertAll(rows.stream().map(row -> () ->
                    assertNull(row.get(absence.field()),
                            () -> "'" + absence.field() + "' reads as "
                                    + row.get(absence.field()) + " where it is absent: " + absence.because())));
        }));
    }

    /**
     * And a record that cannot be read is complained about by name. The loader
     * adds the record selector and the table; which record it was is the
     * adapter's to say, and nobody else's.
     */
    @Test
    public void aFailureNamesTheRecordItHappenedAt() {
        var breakages = breakages();
        assumeFalse(breakages.isEmpty(),
                "this adapter supplied no unreadable sample, so obligation 7 went unchecked");
        assertAll(breakages.stream().map(breakage -> () -> {
            var thrown = assertThrows(Exception.class,
                    () -> drain(adapter(spec()).parse(new ByteArrayInputStream(breakage.sample()),
                            recordSelector().name(), fieldNames())),
                    () -> "the broken sample was read without complaint: " + breakage.because());
            var message = thrown.getMessage();
            assertNotNull(message,
                    () -> thrown.getClass().getSimpleName() + " carries no message at all: " + breakage.because());
            assertTrue(message.contains(breakage.identifies()),
                    () -> "the complaint does not say which record it was - '" + breakage.identifies()
                            + "' is not in: " + message);
        }));
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
        return rowsOf(adapter, sample());
    }

    private List<Row> rowsOf(InputAdapter adapter, byte[] bytes) throws IOException {
        try (var rows = adapter.parse(new ByteArrayInputStream(bytes),
                recordSelector().name(), fieldNames()).rows()) {
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
