package com.pd.xldr.app;

import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.io.JsonMappingSpecReader;
import com.pd.xldr.spec.io.MappingSpecReader;
import com.pd.xldr.spec.io.PropertiesMappingSpecReader;
import com.pd.xldr.spec.io.XmlMappingSpecReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: xldr <mapping-spec-file> <input-file>");
            System.exit(2);
            return;
        }
        var mappingSpec = readMappingSpec(Path.of(args[0]));
        var input = Path.of(args[1]);
        var rows = new LoadJob(mappingSpec).load(input);
        System.out.printf("inserted %d row(s) from %s%n", rows, input);
    }

    static MappingSpec readMappingSpec(Path file) throws IOException {
        var reader = readerFor(file);
        try (var in = Files.newBufferedReader(file)) {
            return reader.readFrom(in);
        }
    }

    /**
     * Picks the reader by file extension.
     * <p>
     * {@code MappingSpecReader} carries no discriminator - no
     * {@code accepts(...)} the way {@code InputAdapterFactory} has one - so the
     * implementations cannot be told apart through {@code ServiceLoader} even
     * though {@code spec} declares them as providers. Hence the explicit switch.
     */
    private static MappingSpecReader readerFor(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return new JsonMappingSpecReader();
        } else if (name.endsWith(".xml")) {
            return new XmlMappingSpecReader();
        } else if (name.endsWith(".properties")) {
            return new PropertiesMappingSpecReader();
        } else {
            throw new IllegalArgumentException("unsupported mapping spec format: " + file);
        }
    }
}
