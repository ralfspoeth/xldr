package com.pd.xldr.spec;

/**
 * When the loader commits the work of a load.
 */
public enum CommitPolicy {

    /**
     * One transaction for the whole input: the loader commits when it is closed,
     * or rolls everything back if any record mapping failed. All or nothing.
     */
    ON_CLOSE,

    /**
     * Commit after each record mapping that completed successfully. Bounds the
     * size of the transaction, at the price of leaving a partial load behind
     * when a later mapping fails.
     */
    PER_MAPPING
}
