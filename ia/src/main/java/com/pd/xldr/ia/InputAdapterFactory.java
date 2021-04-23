package com.pd.xldr.ia;

import com.pd.xldr.spec.InputSpec;

public interface InputAdapterFactory {
    boolean accepts(String mimeType);

    default boolean accepts(InputSpec spec) {
        return accepts(spec.mimeType());
    }

    InputAdapter createInputAdapter(InputSpec spec);
}
