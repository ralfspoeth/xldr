package io.github.ralfspoeth.xldr.xml;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Set;

/**
 * Creates XML adapters.
 * <p>
 * Recognised properties:
 * <ul>
 *   <li>{@code ns.<prefix>} - binds a namespace prefix for use in the selectors,
 *       for example {@code ns.f=http://example.com/funds} to make
 *       {@code //f:fund} match.</li>
 *   <li>{@code dateFormat}, {@code numberFormat}, {@code locale} - the shared
 *       conversion settings, see {@link io.github.ralfspoeth.xldr.ia.Formats}.</li>
 * </ul>
 */
public class XmlFileHandlerFactory implements InputAdapterFactory {

    private static final Set<String> ACCEPT = Set.of("text/xml", "application/xml");

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new XmlFileHandler(spec);
    }
}
