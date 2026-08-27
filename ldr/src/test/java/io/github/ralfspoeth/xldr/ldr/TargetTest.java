package io.github.ralfspoeth.xldr.ldr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a target holds, which is all it does.
 * <p>
 * Turning one into a qualified name is the loader's, and is tested in
 * {@link LoaderTest} against a real database - that being the only place the
 * question "will this database take a schema in an insert?" has an answer.
 */
class TargetTest {

    @Test
    void noneHoldsNeitherPart() {
        assertAll(
                () -> assertTrue(Target.none().isEmpty()),
                () -> assertEquals("unqualified", Target.none().toString()));
    }

    @Test
    void aTargetWithEitherPartIsNotEmpty() {
        assertAll(
                () -> assertFalse(new Target(null, "staging").isEmpty()),
                () -> assertFalse(new Target("warehouse", null).isEmpty()));
    }

    /**
     * Blank is refused rather than treated as absent. A half-edited
     * {@code schema=} reaches here as the empty string only if something
     * upstream decided it was a value, and a name made of nothing would produce
     * a qualifier that is a bare dot.
     */
    @Test
    void ablankPartIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Target(null, "  ")),
                () -> assertThrows(IllegalArgumentException.class, () -> new Target("", null)));
    }

    /** the toString says what the deployment wrote, for a log line */
    @Test
    void saysWhatItHolds() {
        assertAll(
                () -> assertEquals("schema staging", new Target(null, "staging").toString()),
                () -> assertEquals("catalog warehouse", new Target("warehouse", null).toString()),
                () -> assertEquals("catalog warehouse, schema staging",
                        new Target("warehouse", "staging").toString()));
    }

    /** a record, so two targets saying the same thing are the same target */
    @Test
    void isAvalue() {
        assertEquals(new Target("w", "s"), new Target("w", "s"));
    }

    /**
     * And two targets naming one schema are one target, however they spell it -
     * the parts being {@link io.github.ralfspoeth.xldr.spec.SqlIdentifier}s
     * since 0.45, so a deployment saying {@code Staging} means what one saying
     * {@code STAGING} means. A quoted name is exact, as everywhere else.
     */
    @Test
    void twoSpellingsOfOneSchemaAreOneTarget() {
        assertAll(
                () -> assertEquals(new Target(null, "staging"), new Target(null, "STAGING")),
                () -> assertEquals(new Target("w", "s"), new Target("W", "S")),
                () -> assertNotEquals(new Target(null, "\"staging\""), new Target(null, "staging")));
    }
}
