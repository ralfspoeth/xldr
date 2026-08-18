package io.github.ralfspoeth.xldr.xlet;

import java.util.Map;

/**
 * What this servlet is doing, over JMX - registered as
 * {@code io.github.ralfspoeth.xldr:type=Loader,context=…,name=…}.
 * <p>
 * The same argument as in the file server: JMX costs no dependency, which is why
 * this project uses {@code System.Logger} and {@code ServiceLoader} too, and it
 * forecloses nothing - a Prometheus JMX exporter reads it without either side
 * knowing about the other. Everything here is read-only. A servlet is configured
 * by its deployment descriptor, and a management console that could change the
 * concurrency at runtime would be a second place the answer came from.
 * <p>
 * <strong>Read the counters against the settings.</strong> The three settings are
 * exposed for that reason and no other: {@link #getLoadsRejected()} rising is
 * only meaningful next to {@link #getMaxConcurrentLoads()} and
 * {@link #getAcquireTimeoutMillis()}, and an operator should not have to open
 * {@code web.xml} to find out which dial to turn. Rejections climbing while
 * {@link #getLoadsInProgress()} sits at the maximum is a concurrency limit that
 * is too low or a database that is too slow; rejections climbing while it does
 * not is an acquire timeout that is too short.
 * <p>
 * The counters run from the moment the servlet was initialised, so they are rates
 * to be differenced rather than state. Only {@link #getLoadsInProgress()} is
 * state.
 */
public interface XletMXBean {

    /** @return how many loads may run at once, from the {@code maxConcurrentLoads} init-param */
    int getMaxConcurrentLoads();

    /** @return how long a request waits for a permit before it is told to come back */
    long getAcquireTimeoutMillis();

    /** @return the largest body this servlet will spool, in bytes */
    long getMaxBytes();

    /** @return how many requests are being loaded at this moment */
    int getLoadsInProgress();

    /** @return how many requests have been loaded since the servlet started */
    long getLoadsSucceeded();

    /**
     * @return how many loads failed and were rolled back. Nothing is kept when
     * one does - unlike the file server, which has a hospital, this told the
     * caller and let go - so this counter is the only trace, and the log the
     * only detail
     */
    long getLoadsFailed();

    /** @return how many records have been inserted, across every spec */
    long getRecordsLoaded();

    /** @return when a request was last loaded, or empty if none has been */
    String getLastLoad();

    /** @return when a load last failed, or empty if none has */
    String getLastFailure();

    /**
     * @return how many requests were refused before any load was attempted: a
     * missing or unknown {@code spec}, a content type the spec's adapter does not
     * read, a body too large to accept, a path below the mapping. These are the
     * caller's mistakes rather than this deployment's, and a number that keeps
     * climbing is a client that has not been told
     */
    long getRequestsRefused();

    /**
     * @return how many requests were turned away with {@code 503} because no
     * permit came free in time. This is the deployment's own limit, not the
     * caller's mistake, and it is the number the concurrency settings are judged
     * by
     */
    long getLoadsRejected();

    /**
     * @return one row per spec the deployment carries, whether or not it has ever
     * loaded - the analogue of the file server's feed table. A spec that has
     * never loaded is worth seeing: it is either unused or unreachable, and the
     * difference is not visible from a total
     */
    Map<String, SpecStatus> getSpecs();
}
