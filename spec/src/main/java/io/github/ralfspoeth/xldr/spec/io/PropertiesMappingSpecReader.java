package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.LoadSpec;
import io.github.ralfspoeth.dirs.Directory;
import io.github.ralfspoeth.dirs.Path;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Properties;

public class PropertiesMappingSpecReader implements MappingSpecReader {

    private static void insert(String name, String value, Directory<String, String> into) {
        var index = name.indexOf('.');
        if (index > -1) {
            var group = name.substring(0, index);
            var remainder = name.substring(index + 1);
            insert(remainder, value, into.addSubDirsIfAbsent(Path.of(group)));
        } else {
            into.addResource(name, value);
        }
    }

    private static Directory<String, String> parse(Properties props) {
        var propsHierarchy = new Directory<String, String>();
        props.stringPropertyNames().forEach(
                n -> insert(n, props.getProperty(n), propsHierarchy)
        );
        return propsHierarchy;
    }

    @Override
    public MappingSpec readFrom(Reader source) throws IOException {
        var props = new Properties();
        props.load(source);
        var _ = parse(props);

        return new MappingSpec(
                new InputSpec(null, null),
                new ArrayList<>(),
                new LoadSpec()
        );
    }
}
