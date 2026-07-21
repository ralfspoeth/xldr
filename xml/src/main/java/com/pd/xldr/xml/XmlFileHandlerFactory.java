package com.pd.xldr.xml;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.InputAdapterFactory;
import com.pd.xldr.spec.InputSpec;

import java.util.Properties;
import java.util.Set;

public class XmlFileHandlerFactory implements InputAdapterFactory {

    private static final Set<String> ACCEPT = Set.of("text/xml", "application/xml");
    private final Properties properties = new Properties();

    @Override
    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new XmlFileHandler(spec);
    }

    @Override
    public boolean accepts(String mimeType) {
        return ACCEPT.contains(mimeType);
    }
}
