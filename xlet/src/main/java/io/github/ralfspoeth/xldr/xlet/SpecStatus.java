package io.github.ralfspoeth.xldr.xlet;

/**
 * What has been loaded through one spec.
 * <p>
 * A record rather than a bean, for the reason {@code FeedStatus} is one: the
 * MXBean framework maps a record to {@code CompositeData} by its components and
 * rebuilds it through the canonical constructor, which a client-side proxy needs
 * in order to hand the value back as this type.
 * <p>
 * The counterpart of the file server's {@code FeedStatus}, minus everything about
 * files. There is no {@code filesWaiting} here because nothing waits - a request
 * is either being loaded or it is over - and no {@code filesInHospital} because
 * nothing is kept: the caller was told what went wrong and still has the data.
 * What is left is what both front ends count, which is why the counters
 * themselves live in {@code ldr} and only this shape lives here.
 *
 * @param name           the spec's name, which is its file's base name under
 *                       {@code /WEB-INF/specs/}
 * @param loadsSucceeded how many requests this spec has loaded since the servlet
 *                       started
 * @param loadsFailed    how many failed and were rolled back
 * @param recordsLoaded  how many records it has inserted
 * @param lastLoad       when it last loaded, or empty if it has not
 * @param lastFailure    when a load of its last failed, or empty if none has
 */
public record SpecStatus(
        String name,
        long loadsSucceeded,
        long loadsFailed,
        long recordsLoaded,
        String lastLoad,
        String lastFailure
) {}
