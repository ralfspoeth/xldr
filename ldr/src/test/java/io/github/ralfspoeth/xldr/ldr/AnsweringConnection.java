package io.github.ralfspoeth.xldr.ldr;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Map;

/**
 * A connection whose {@link DatabaseMetaData} answers what a test says it does.
 * <p>
 * The loader asks a driver two questions before it builds a name - whether this
 * database takes a catalog, and whether it takes a schema, in data manipulation.
 * H2 answers yes to both, so the interesting combinations have no driver in this
 * build to produce them: PostgreSQL is the one that says no to catalogs, being
 * unable to qualify across databases, and it is not on the test path.
 * <p>
 * This is not a fake database. Nothing here executes anything: the answers are
 * the <em>input</em> to the code under test, which is the code that decides what
 * to do with what a driver reports. A test that could only ask H2 would be
 * testing H2's opinion rather than ours.
 * <p>
 * A proxy rather than a written-out implementation because {@link Connection}
 * has upwards of fifty methods and this needs two of them. Anything else being
 * called is a change in what the loader does before it has a statement, and
 * throwing there is the right way to find out.
 */
final class AnsweringConnection {

    private AnsweringConnection() {
    }

    /**
     * @param answers method name to answer, for the metadata calls a test cares
     *                about; anything else asked of the metadata is unstubbed and
     *                fails loudly
     */
    static Connection answering(Map<String, Object> answers) {
        var meta = (DatabaseMetaData) Proxy.newProxyInstance(
                AnsweringConnection.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    var answer = answers.get(method.getName());
                    if (answer == null) {
                        throw new UnsupportedOperationException(
                                "this test's connection was not told what to answer for "
                                        + method.getName() + "; it knows " + answers.keySet());
                    }
                    return answer;
                });
        return (Connection) Proxy.newProxyInstance(
                AnsweringConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> meta;
                    // the constructor turns auto-commit off once the target is
                    // settled, and that is the last thing it does to a connection
                    // when a spec has no vars. Permitted so that the accepted
                    // cases can reach the end of it
                    case "setAutoCommit" -> null;
                    default -> throw new UnsupportedOperationException(
                            "this connection answers metadata and setAutoCommit, and was asked for "
                                    + method.getName() + " - which means the loader got further "
                                    + "than deciding the target, and this test cannot say whether "
                                    + "that is right");
                });
    }

    /** what a database that takes both parts answers */
    static Map<String, Object> takesBoth() {
        return Map.of(
                "supportsCatalogsInDataManipulation", true,
                "supportsSchemasInDataManipulation", true,
                "getDatabaseProductName", "Obliging",
                "getCatalogTerm", "catalog",
                "getSchemaTerm", "schema");
    }

    /** and what PostgreSQL answers, which cannot qualify across databases */
    static Map<String, Object> refusesCatalogs() {
        var answers = new java.util.HashMap<>(takesBoth());
        answers.put("supportsCatalogsInDataManipulation", false);
        answers.put("getDatabaseProductName", "PostgreSQL");
        answers.put("getCatalogTerm", "database");
        return answers;
    }

    static Map<String, Object> refusesSchemas() {
        var answers = new java.util.HashMap<>(takesBoth());
        answers.put("supportsSchemasInDataManipulation", false);
        answers.put("getDatabaseProductName", "Flat");
        return answers;
    }
}
