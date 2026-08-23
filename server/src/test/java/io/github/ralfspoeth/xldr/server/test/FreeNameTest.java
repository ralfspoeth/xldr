package io.github.ralfspoeth.xldr.server.test;

import io.github.ralfspoeth.xldr.server.FreeName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one function every file this server moves passes through.
 * <p>
 * An input claimed into {@code work/}, one archived after a load, one sent to
 * {@code hospital/}, one recovered from a process that died - all four ask for a
 * free name and then move without {@code REPLACE_EXISTING}. So the whole of the
 * server's promise that it never overwrites what it was handed rests here, and
 * until now nothing tested it: it was a private method of a package-private
 * class, reachable only through a running server.
 */
class FreeNameTest {

    /** the plain name, where nothing is in the way */
    @Test
    void takesTheNameAsGivenWhenItIsFree(@TempDir Path dir) {
        Assertions.assertEquals(dir.resolve("data.csv"), FreeName.in(dir, "data.csv", "STAMP"));
    }

    /**
     * The discriminator goes before the extension, so that whoever finds the file
     * still recognises what it is.
     */
    @Test
    void putsTheDiscriminatorBeforeTheExtension(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("data.csv"));
        assertEquals(dir.resolve("data.STAMP.csv"), FreeName.in(dir, "data.csv", "STAMP"));
    }

    /** and after the name where there is no extension to go before */
    @Test
    void appendsWhereThereIsNoExtension(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("statement"));
        assertEquals(dir.resolve("statement.STAMP"), FreeName.in(dir, "statement", "STAMP"));
    }

    /** the last dot, so a double extension keeps the part that names the format */
    @Test
    void splitsAtTheLastDot(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("archive.tar.gz"));
        assertEquals(dir.resolve("archive.tar.STAMP.gz"), FreeName.in(dir, "archive.tar.gz", "STAMP"));
    }

    /**
     * The one that matters, and the one that fails today.
     * <p>
     * The plain name is checked and the discriminated one is not, so where both
     * are taken this hands back a path that already exists. Two files of one name
     * reaching the same directory within a millisecond is unlikely and not
     * impossible - a redelivery loop, a bulk drop - and the caller then moves
     * onto an existing file without {@code REPLACE_EXISTING}, which throws.
     * <p>
     * In {@code archive()} that throw arrives <em>after</em> the load has
     * committed: the file is counted as a failure and sent to the hospital with
     * its rows already in the database, and an operator redelivering it loads
     * them twice. A quiet path to duplicated data, which is the shape of defect
     * this server is otherwise built to refuse.
     */
    @Test
    void isStillFreeWhenTheDiscriminatedNameIsTakenToo(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("data.csv"));
        var first = FreeName.in(dir, "data.csv", "STAMP");
        Files.createFile(first);

        var second = FreeName.in(dir, "data.csv", "STAMP");
        assertAll(
                () -> assertFalse(Files.exists(second),
                        "a free name that already exists is not a free name: " + second.getFileName()),
                () -> assertFalse(second.equals(first),
                        "and it is not the one just taken: " + second.getFileName()));
    }

    /**
     * And what the further variants look like, which the test above does not pin:
     * it asks only that the answer be free and different, which several naming
     * schemes would satisfy. An operator reading a directory should be able to
     * tell at a glance that these are one file arriving more than once.
     */
    @Test
    void numbersTheVariantsAfterTheFirst(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("data.csv"));
        Files.createFile(dir.resolve("data.STAMP.csv"));
        assertEquals(dir.resolve("data.STAMP-2.csv"), FreeName.in(dir, "data.csv", "STAMP"));

        Files.createFile(dir.resolve("data.STAMP-2.csv"));
        assertEquals(dir.resolve("data.STAMP-3.csv"), FreeName.in(dir, "data.csv", "STAMP"));
    }

    /**
     * The property the four callers actually rely on, stated once: ask for the
     * same name as often as you like, take each answer, and every answer is new.
     */
    @Test
    void neverHandsBackANameAlreadyTaken(@TempDir Path dir) throws IOException {
        for (int i = 0; i < 5; i++) {
            var free = FreeName.in(dir, "data.csv", "STAMP");
            assertFalse(Files.exists(free), "answer " + i + " was already taken: " + free.getFileName());
            Files.createFile(free);
        }
    }
}
