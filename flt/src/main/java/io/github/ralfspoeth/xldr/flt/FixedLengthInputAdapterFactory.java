package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.HashMap;
import java.util.Map;

public class FixedLengthInputAdapterFactory implements InputAdapterFactory {

    private final Map<String, String> props = new HashMap<>();

    @Override
    public boolean reads(String mimeType) {
        return "text/plain".equals(mimeType);
    }

    @Override
    public void setProperty(String property, String value) {
        props.put(property, value);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new FixedLengthInputAdapter(Integer.parseInt(
                props.getOrDefault("linesPerRecord", "1"))
        );
    }
}
