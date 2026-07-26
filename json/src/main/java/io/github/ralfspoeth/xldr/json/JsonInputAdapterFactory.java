package io.github.ralfspoeth.xldr.json;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Creates JSON adapters.
 * <p>
 * Recognised properties:
 * <ul>
 *   <li>{@code charset} - how the document is decoded; defaults to UTF-8, which
 *       is what JSON is written in;</li>
 *   <li>{@code dateFormat}, {@code numberFormat}, {@code locale} - the shared
 *       conversion settings, see {@link Formats}. They apply to values carried
 *       as JSON strings; a JSON number is already a number.</li>
 * </ul>
 */
public class JsonInputAdapterFactory implements InputAdapterFactory {

    private static final Set<String> ACCEPT = Set.of("text/json", "application/json");

    private final Map<String, String> props = new HashMap<>();

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public void setProperty(String property, String value) {
        props.put(property, value);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new JsonInputAdapter(
                props.containsKey("charset")
                        ? Charset.forName(props.get("charset"))
                        : StandardCharsets.UTF_8,
                Formats.of(props),
                spec);
    }
}
