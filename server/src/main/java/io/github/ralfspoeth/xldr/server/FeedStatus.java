package io.github.ralfspoeth.xldr.server;

/**
 * What one feed has done and what is waiting for it.
 * <p>
 * A record rather than a bean: the MXBean framework maps a record to
 * {@code CompositeData} by its components and rebuilds it through the canonical
 * constructor, which a client-side proxy needs in order to hand the value back
 * as this type.
 *
 * @param name            the feed's directory name
 * @param state           whether it can load, or is still waiting for a mapping
 *                        spec. A {@code PENDING} feed still counts the files
 *                        waiting in its {@code in/}, which is where they stay
 *                        until a spec turns up
 * @param loadsSucceeded  how many files this feed has loaded since the server started
 * @param loadsFailed     how many of its loads failed and were hospitalised
 * @param recordsLoaded   how many records it has inserted
 * @param lastLoad        when it last loaded a file, or empty if it has not
 * @param lastFailure     when a load of its last failed, or empty if none has
 * @param filesWaiting    how many files are sitting in its {@code in/} - a number
 *                        that ought to fall back to zero, and whose not doing so
 *                        is the sign of a feed that is not claiming what arrives
 * @param filesInHospital how many files are in its {@code hospital/}, which is
 *                        the alert worth watching: nothing puts a file there but
 *                        a failure, and nothing takes one away but an operator.
 *                        The {@code .log} beside each one explains a failure and
 *                        is not counted as another
 */
public record FeedStatus(
        String name,
        FeedState state,
        long loadsSucceeded,
        long loadsFailed,
        long recordsLoaded,
        String lastLoad,
        String lastFailure,
        int filesWaiting,
        int filesInHospital
) {}
