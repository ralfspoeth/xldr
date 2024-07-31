package com.pd.xldr.spec.io;

import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.OutputSpec;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Properties;

public class PropertiesMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader source) throws IOException {
        var props = new Properties();
        props.load(source);
        //outputSpec spec
        var outProps = new HashMap<String, String>();
        props.stringPropertyNames()
                .stream()
                .filter(n -> n.startsWith("outputSpec.") && !n.equals("outputSpec.url"))
                .forEach(name -> outProps.put(name, props.getProperty(name)));
        var os = new OutputSpec(props.getProperty("outputSpec.url"), outProps);
        // inputSpec spec
        var recMap = new HashMap<String, String>();
        props.stringPropertyNames()
                .stream()
                .filter(n -> n.startsWith("inputSpec.") && !n.equals("inputSpec.mimeType"))
                .forEach(n -> {

                });

        return null;
    }
}
