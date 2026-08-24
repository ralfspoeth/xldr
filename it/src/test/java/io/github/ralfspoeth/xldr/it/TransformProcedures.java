package io.github.ralfspoeth.xldr.it;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The procedures {@link TransformIT} asks H2 to call.
 * <p>
 * Public, and a top-level class, which is an exception to this repository's rule
 * that a test class is package-private - the same exception {@code
 * XldrServletIT.Deployed} is, and for the same kind of reason. H2 binds a
 * procedure with {@code CREATE ALIAS ... FOR 'fully.qualified.Class.method'},
 * loads the class by name and invokes the method reflectively, so both have to
 * be reachable from outside. A nested class would not do either: H2 splits that
 * string at the last dot, so the class name it looks up cannot contain one.
 * <p>
 * The other form H2 offers, {@code CREATE ALIAS ... AS $$ java source $$},
 * compiles the body at run time and therefore wants the JDK's compiler reachable
 * from H2's own module - which is a thing to discover on somebody's machine
 * rather than in a test that is about transactions.
 */
public final class TransformProcedures {

    private TransformProcedures() {
    }

    /**
     * Writes down what it was told and what it can see.
     * <p>
     * The second is the point: a count of the rows this load inserted, taken on
     * the load's own connection, is the only way a test can establish afterwards
     * that the procedure ran before the commit rather than after it.
     * <p>
     * H2 hands the calling connection to a procedure whose first parameter is a
     * {@link Connection}, and does not count it among the arguments the caller
     * binds - so xldr's two arguments arrive as the second and third.
     */
    public static void closeBatch(Connection connection, String feed, int rowsLoaded) throws SQLException {
        int seen;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from shipment")) {
            rows.next();
            seen = rows.getInt(1);
        }
        try (var insert = connection.prepareStatement("insert into batch_log values(?, ?, ?)")) {
            insert.setString(1, feed);
            insert.setInt(2, rowsLoaded);
            insert.setInt(3, seen);
            insert.executeUpdate();
        }
    }

    /** a procedure that fails, for the half of the contract about rolling back */
    public static void explode() throws SQLException {
        throw new SQLException("this transform refuses to run");
    }
}
