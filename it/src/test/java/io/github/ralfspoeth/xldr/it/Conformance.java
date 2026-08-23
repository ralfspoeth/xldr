package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.*;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Shorthand for the five conformance tests beside this class.
 * <p>
 * They live in {@code it} rather than in each adapter's own module because the
 * adapters' tests are patched into the module they test, and a patched test has
 * no descriptor in which to {@code requires} the kit. This module has one, and it
 * already requires all five adapters.
 */
final class Conformance {

    private Conformance() {
    }

    /**
     * The factory for a spec, found the only way these five can be reached.
     * <p>
     * None of the shipped adapter modules exports anything: the factory class is
     * public because {@code provides ... with} needs it to be, and the package
     * stays closed, so {@code new CsvFileHandlerFactory()} is not something any
     * other module can write. Discovery is the whole interface, which is the
     * point of the arrangement.
     * <p>
     * An adapter author testing their own module is in the easier position and
     * may hand the kit a constructor call; this is what it looks like from
     * outside.
     */
    static InputAdapterFactory discovered(InputSpec spec) {
        return InputAdapterFactory.of(spec).orElseThrow(() -> new IllegalStateException(
                "no adapter claims " + spec.mimeType() + "; is its module required by this test module?"));
    }

    static InputSpec spec(String mimeType, Map<String, String> properties,
                          Locator locator, FieldSelectorSpec... fields) {
        return new InputSpec(mimeType,
                List.of(new RecordSelectorSpec("records", locator, List.of(fields))),
                List.of(), properties);
    }

    static FieldSelectorSpec field(String name, String selector, DataType type) {
        return new FieldSelectorSpec(name, selector, type);
    }

    static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }
}
