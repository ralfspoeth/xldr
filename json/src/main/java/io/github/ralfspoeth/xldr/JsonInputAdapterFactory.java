package io.github.ralfspoeth.xldr;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class JsonInputAdapterFactory implements InputAdapterFactory {

    private final Map<String, String> props = new HashMap<>();

    @Override
    public boolean reads(String mimeType) {
        return Set.of("text/json", "application/json").contains(mimeType);
    }

    @Override
    public void setProperty(String property, String value) {
        props.put(property, value);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new JsonInputAdapter();
    }
}
