package io.github.ralfspoeth.xldr.ldr;

import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.xldr.ldr.AnsweringConnection.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the loader does with a target, according to what the driver says it may.
 * <p>
 * Every case here is decided in the constructor, before a statement exists and
 * before auto-commit is touched - which is what makes a connection that answers
 * metadata and nothing else sufficient. If one of these ever gets as far as
 * asking the connection for anything else, the connection throws and says so,
 * because that would be a change in when the decision is made.
 * <p>
 * The combinations matter because no driver in this build produces them. H2 says
 * yes to catalogs and schemas alike, so a deployment that hits the refusal is
 * one running against PostgreSQL - and finding out there, on the first record of
 * the first file, is what this exists to prevent.
 */
class QualifyingTest {

    /** the smallest spec there is: the target is decided before any mapping is read */
    private static final MappingSpec SPEC = new MappingSpec(
            new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of());

    private static SQLException refused(Map<String, Object> answers, Target target) {
        return assertThrows(SQLException.class,
                () -> new Loader(SPEC, answering(answers), Map.of(), target));
    }

    // ---- what a database will not take ----------------------------------------

    /**
     * PostgreSQL cannot qualify across databases, so a catalog is not something a
     * spec can ever load against it. The message names the catalog, the product
     * and the database's own word for the thing - {@code database}, not
     * {@code catalog}, which is what its documentation calls it.
     */
    @Test
    void acatalogIsRefusedWhereTheDatabaseTakesNone() {
        var thrown = refused(refusesCatalogs(), new Target("warehouse", null));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("warehouse"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("PostgreSQL"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("database"),
                        "the product's own term, not ours: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("target.properties"),
                        "and where to remove it: " + thrown.getMessage()));
    }

    @Test
    void aschemaIsRefusedWhereTheDatabaseTakesNone() {
        var thrown = refused(refusesSchemas(), new Target(null, "staging"));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("staging"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("schema"), thrown.getMessage()));
    }

    /**
     * The catalog is checked first, so a target with both against a database that
     * takes neither complains about the catalog. Either message would be true;
     * what matters is that one of them arrives rather than a syntax error later.
     */
    @Test
    void thefirstUnusablePartIsTheOneReported() {
        var thrown = refused(refusesCatalogs(), new Target("warehouse", "staging"));
        assertTrue(thrown.getMessage().contains("warehouse"), thrown.getMessage());
    }

    // ---- and what it will -------------------------------------------------------

    /**
     * A target whose parts the database takes is resolved without complaint. The
     * connection here answers metadata and refuses everything else, so reaching
     * the end of the constructor is itself the assertion: the decision was made
     * from the metadata and nothing was executed.
     */
    @Test
    void atargetTheDatabaseTakesIsAccepted() {
        assertAll(
                () -> assertDoesNotThrow(() -> qualifierFor(takesBoth(), new Target(null, "staging"))),
                () -> assertDoesNotThrow(() -> qualifierFor(takesBoth(), new Target("w", "s"))),
                () -> assertDoesNotThrow(() -> qualifierFor(takesBoth(), new Target("w", null))));
    }

    /**
     * An empty target asks the driver nothing at all - the metadata here would
     * throw on any call it was not told about, and it is told about nothing.
     * <p>
     * Worth pinning: it is the overwhelmingly common case, and a round trip per
     * load to ask permission for a qualifier that will not be written would be a
     * cost paid by every deployment for the benefit of a few.
     */
    @Test
    void anEmptyTargetAsksTheDriverNothing() {
        assertDoesNotThrow(() -> new Loader(SPEC, answering(Map.of()), Map.of(), Target.none()));
    }

    private static void qualifierFor(Map<String, Object> answers, Target target) throws SQLException {
        // the constructor is where a target is resolved; there is nothing else to
        // call, and nothing else is what this connection permits
        new Loader(SPEC, answering(answers), Map.of(), target);
    }
}
