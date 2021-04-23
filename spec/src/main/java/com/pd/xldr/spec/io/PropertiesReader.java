package com.pd.xldr.spec.io;

import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.OutputSpec;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Properties;

final class PropertiesReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader source) throws IOException {
        var props = new Properties();
        props.load(source);
        //output spec
        var outProps = new Properties();
        props.stringPropertyNames()
                .stream()
                .filter(n -> n.startsWith("output.") && !n.equals("output.url"))
                .forEach(name -> outProps.setProperty(name, props.getProperty(name)));
        var os = new OutputSpec(props.getProperty("output.url"), outProps);
        // input spec
        var recMap = new HashMap<String, String>();
        props.stringPropertyNames()
                .stream()
                .filter(n -> n.startsWith("input.") && !n.equals("input.mimeType"))
                .forEach(n -> {

                });

        return null;
    }
}
