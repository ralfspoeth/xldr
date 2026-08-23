package io.github.ralfspoeth.xldr.json;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Set;

/**
 * Creates JSON adapters.
 * <p>
 * There is no charset setting: JSON exchanged between systems is UTF-8 by
 * definition (RFC 8259), so the document is always decoded as such.
 * <p>
 * Recognised properties are {@code dateFormat}, {@code numberFormat} and
 * {@code locale}, the shared conversion settings - see {@link Formats}. They
 * apply to values carried as JSON strings; a JSON number is already a number.
 */
public class JsonInputAdapterFactory implements InputAdapterFactory {

    private static final Set<String> ACCEPT = Set.of("text/json", "application/json");

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new JsonInputAdapter(Formats.of(spec.properties()), spec);
    }
}
