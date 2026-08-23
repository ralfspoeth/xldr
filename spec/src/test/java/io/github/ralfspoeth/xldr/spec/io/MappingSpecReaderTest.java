package io.github.ralfspoeth.xldr.spec.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import java.util.List;

import static io.github.ralfspoeth.xldr.spec.io.MappingSpecReader.readSpec;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Which reader reads a spec file is decided by {@link MappingSpecReader#accepts},
 * and the server asks each reader in whatever order the service loader hands
 * them over. So the answers have to partition the names between them: exactly
 * one reader for a name it should read, and none at all for a name neither
 * format owns.
 */
class MappingSpecReaderTest {

    private static final MappingSpecReader JSON = new JsonMappingSpecReader();
    private static final MappingSpecReader XML = new XmlMappingSpecReader();

    @Test
    void eachReaderTakesItsOwnExtension() {
        assertAll(
                () -> assertTrue(JSON.accepts(Path.of("spec.json"))),
                () -> assertFalse(JSON.accepts(Path.of("spec.xml"))),
                () -> assertTrue(XML.accepts(Path.of("spec.xml"))),
                () -> assertFalse(XML.accepts(Path.of("spec.json")))
        );
    }

    /**
     * The file name decides, not the path it sits at. A {@code *.json} glob
     * matched against a whole path would founder on the separators, which is
     * why the readers ask for the name alone - and a feed's spec is always
     * addressed by its full path.
     */
    @Test
    void aSpecDeepInAtreeIsStillTakenByItsName() {
        assertAll(
                () -> assertTrue(JSON.accepts(Path.of("var", "lib", "xldr", "people", "spec.json"))),
                () -> assertTrue(XML.accepts(Path.of("var", "lib", "xldr", "funds", "spec.xml")))
        );
    }

    /**
     * A name in no known format is taken by nobody, so the server can say the
     * format is unsupported rather than handing the file to whichever reader
     * happened to be asked first.
     */
    @Test
    void refusesAnameNeitherFormatOwns() {
        for (var name : List.of("spec.txt", "spec", "specjson", "spec.json.bak", "spec.xml.tmp")) {
            var path = Path.of(name);
            assertAll(
                    () -> assertFalse(JSON.accepts(path), name + " is not JSON"),
                    () -> assertFalse(XML.accepts(path), name + " is not XML")
            );
        }
    }

    /**
     * The lookup itself: the readers are registered as services, and
     * {@link MappingSpecReader#of} finds the one that takes the file. This also
     * covers the {@code provides} clause of the module, which nothing else
     * would notice going missing.
     */
    @Test
    void findsTheReaderForAspecFile() {
        assertAll(
                () -> assertInstanceOf(JsonMappingSpecReader.class,
                        MappingSpecReader.of(Path.of("people", "spec.json")).orElseThrow()),
                () -> assertInstanceOf(XmlMappingSpecReader.class,
                        MappingSpecReader.of(Path.of("funds", "spec.xml")).orElseThrow()),
                () -> assertTrue(MappingSpecReader.of(Path.of("spec.txt")).isEmpty(),
                        "an unknown format has no reader")
        );
    }

    /**
     * The same spec in both formats reads to the same thing, each through the
     * reader its name calls for - which is what {@code readSpec(Path)} is for:
     * a caller with a spec file in hand names the file and gets the spec.
     */
    @Test
    void readsAspecFileInEitherFormat(@TempDir Path dir) throws IOException {
        var json = Files.writeString(dir.resolve("spec.json"), """
                {
                  "input": { "mimeType": "text/csv", "recordSelectors": [ { "name": "people",
                      "fieldSelectors": [ { "name": "id", "selector": "id", "type": "INTEGRAL" } ] } ] },
                  "mapping": [ { "recordSelector": "people", "table": "person",
                    "fieldMapping": [ { "fieldSelector": "id", "column": "id" } ] } ]
                }
                """);
        var xml = Files.writeString(dir.resolve("spec.xml"), """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <recordSelector name="people">
                            <fieldSelector name="id" selector="id" type="INTEGRAL"/>
                        </recordSelector>
                    </input>
                    <mapping recordSelector="people" table="person">
                        <fieldMapping fieldSelector="id" column="id"/>
                    </mapping>
                </mappingSpec>
                """);

        assertEquals(readSpec(json), readSpec(xml));
    }

    /**
     * A {@code limit} is a whole number in both formats, and it was not always:
     * the XML reader parsed the attribute and threw on anything else, while the
     * JSON reader asked for an int and read {@code "100"} as no limit at all - a
     * spec that meant to load a thousand rows and loaded the file. The rule now
     * lives in one place, so the two cannot answer differently.
     */
    @Test
    void aLimitIsAWholeNumberInEitherFormat() {
        assertAll(
                () -> assertRefused(() -> JSON.read(bytes("""
                        { "input": { "mimeType": "text/csv", "recordSelectors": [] },
                          "mapping": [ { "recordSelector": "people", "table": "person",
                            "limit": "100", "fieldMapping": [] } ] }
                        """))),
                () -> assertRefused(() -> XML.read(bytes("""
                        <mappingSpec>
                            <input mimeType="text/csv"/>
                            <mapping recordSelector="people" table="person" limit="100 rows"/>
                        </mappingSpec>
                        """))));
    }

    private static InputStream bytes(String spec) {
        return new ByteArrayInputStream(spec.getBytes(UTF_8));
    }

    private static void assertRefused(Executable read) {
        var thrown = assertThrows(IllegalArgumentException.class, read);
        assertTrue(String.valueOf(thrown.getMessage()).contains("whole number"),
                () -> "expected a message about a whole number, was: " + thrown.getMessage());
    }

    /**
     * A file no reader claims is refused by name, before it is opened - the
     * caller is told the format is unsupported rather than handed a parse error
     * about a format the file was never in.
     */
    @Test
    void refusesToReadAnUnknownFormat(@TempDir Path dir) throws IOException {
        var file = Files.writeString(dir.resolve("spec.txt"), "{}");
        var thrown = assertThrows(IllegalArgumentException.class, () -> readSpec(file));
        assertTrue(thrown.getMessage().contains("unsupported"), thrown.getMessage());
    }

    /**
     * A name a reader claims but no file answers to is an {@code IOException},
     * not an argument problem: the format was fine, the file was not there.
     */
    @Test
    void reportsAmissingFileAsIo(@TempDir Path dir) {
        assertThrows(NoSuchFileException.class,
                () -> readSpec(dir.resolve("spec.json")));
    }
}
