package com.pd.xldr.app;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.InputAdapterFactory;
import com.pd.xldr.spec.InputSpec;

import java.io.File;
import java.util.List;
import java.util.ServiceLoader;

public class Main {

    public static void main(String[] args) {
        var ia = handler(new File("/tmp/io/Input.csv"));
        System.out.println(ia);
    }

    static InputAdapter handler(File file) {
        var spec = new InputSpec("text/csv", List.of());
        var fhf = ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(fh -> fh.accepts(spec))
                .findFirst()
                .orElseThrow();
        return fhf.createInputAdapter(spec);
    }
}
