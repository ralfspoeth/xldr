package io.github.ralfspoeth.xldr.ldr;

import io.github.ralfspoeth.xldr.spec.SqlIdentifier;
import org.jspecify.annotations.Nullable;

/**
 * Where a load's tables live, when the connection does not already say.
 * <p>
 * A mapping spec names a table and nothing more, on purpose: the same spec is
 * meant to travel from test to production unchanged, and which schema it lands
 * in is precisely what differs between the two. So it is not in the spec, in the
 * same way and for the same reason that {@code accepts} is not - it is a
 * property of the deployment, and the file server reads it from a
 * {@code target.properties} beside the spec.
 * <p>
 * Both parts are optional and usually absent. A connection made as the owning
 * user, against a database with one schema, needs neither: the unqualified name
 * resolves through whatever search path the session already has. Naming a schema
 * matters where it does not - a service account that reads several, or a staging
 * schema fed by the same specs that feed production.
 * <p>
 * <strong>A value, not a renderer.</strong> This carries what the deployment
 * said and validates it; turning it into a qualified name is
 * {@link Loader}'s, because that is the only thing that builds SQL and the only
 * thing holding a {@link java.sql.Connection}. Whether a database will take a
 * catalog or a schema in an insert at all is something only the driver knows,
 * and a type deciding it without one would be guessing.
 * <p>
 * Both parts are {@link SqlIdentifier}s, as every other name that reaches SQL
 * is: they are folded the same way a table name is, and a deployment naming
 * {@code Staging} means the same schema as one naming {@code STAGING} unless it
 * quotes it. Reading them from a properties file is where the wrapping happens,
 * so the loader is handed names rather than strings it has to remember to fold.
 *
 * @param catalog the catalog part, or {@code null} for none
 * @param schema  the schema part, or {@code null} for none
 */
public record Target(@Nullable SqlIdentifier catalog, @Nullable SqlIdentifier schema) {

    private static final Target NONE = new Target((SqlIdentifier) null, null);

    /**
     * Neither part: names go to the database exactly as the spec wrote them.
     * <p>
     * The default everywhere, and what a front end with nowhere to configure one
     * passes. It is not a lesser case - an unqualified name resolving through the
     * session's search path is how most deployments work, and how every one
     * worked before this existed.
     */
    public static Target none() {
        return NONE;
    }

    /**
     * The two parts as a properties file has them, which is where they come from
     * everywhere but a test: text, and absent rather than empty.
     * <p>
     * The blank check is here rather than left to {@link SqlIdentifier} because
     * the message is worth more than the type's generic one. A blank setting is
     * not a mistake about identifiers, it is a line someone half-deleted, and
     * saying "leave it out" is the instruction they need.
     */
    public Target(@Nullable String catalog, @Nullable String schema) {
        this(identifier(catalog, "catalog"), identifier(schema, "schema"));
    }

    private static @Nullable SqlIdentifier identifier(@Nullable String part, String kind) {
        if (part == null) {
            return null;
        }
        if (part.isBlank()) {
            throw new IllegalArgumentException(
                    "a " + kind + " that is blank is not a " + kind + "; leave it out");
        }
        return new SqlIdentifier(part);
    }

    /** whether this target adds anything to a table name */
    public boolean isEmpty() {
        return catalog == null && schema == null;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "unqualified";
        }
        var said = new StringBuilder();
        if (catalog != null) {
            said.append("catalog ").append(catalog);
        }
        if (schema != null) {
            said.append(said.isEmpty() ? "" : ", ").append("schema ").append(schema);
        }
        return said.toString();
    }
}
