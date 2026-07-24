package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

import static java.util.Objects.requireNonNullElse;

/**
 * How a load is to be carried out.
 * <p>
 * Deliberately free of any connection information: which database is fed is a
 * deployment concern configured on the application, not a property of a mapping,
 * so that the same spec can be promoted from test to production unchanged.
 *
 * @param commitPolicy when to commit; defaults to {@link CommitPolicy#ON_CLOSE}
 */
public record LoadSpec(CommitPolicy commitPolicy) implements Serializable {
    public LoadSpec {
        commitPolicy = requireNonNullElse(commitPolicy, CommitPolicy.ON_CLOSE);
    }

    public LoadSpec() {
        this(CommitPolicy.ON_CLOSE);
    }
}
