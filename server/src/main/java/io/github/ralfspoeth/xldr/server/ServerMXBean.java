package io.github.ralfspoeth.xldr.server;

import java.util.Map;

/**
 * What the server is doing, over JMX - registered as
 * {@code io.github.ralfspoeth.xldr:type=Server}.
 * <p>
 * It needs no agent and no dependency: any JMX client reads it, from
 * {@code jconsole} attached to the process to a Prometheus JMX exporter
 * scraping it. Everything here is read-only; nothing can be started, stopped or
 * reset through it, because the file system is the interface for that.
 * <p>
 * The counters run from the start of the process, so they are rates to be
 * differenced rather than absolute state. The two numbers that are state -
 * files waiting and files in the hospital - come from the directories
 * themselves, and are the ones worth alerting on.
 */
public interface ServerMXBean {

    /** @return how many feeds are active, that is have a readable spec */
    int getActiveFeeds();

    /** @return how many files are being loaded at this moment */
    int getLoadsInProgress();

    /** @return how many files have been loaded since the server started */
    long getLoadsSucceeded();

    /** @return how many loads failed and left their input in a hospital */
    long getLoadsFailed();

    /** @return how many records have been inserted, across every feed */
    long getRecordsLoaded();

    /** @return when a file was last loaded, or empty if none has been */
    String getLastLoad();

    /** @return when a load last failed, or empty if none has */
    String getLastFailure();

    /** @return how many files are waiting in the {@code in/} of any feed */
    int getFilesWaiting();

    /**
     * @return how many files are in the {@code hospital/} of any feed; a load
     * that failed put each of them there, and only an operator takes one away.
     * The {@code .log} written beside each one is not counted - it explains a
     * failure rather than being a second one
     */
    int getFilesInHospital();

    /** @return the same, per feed, by feed name */
    Map<String, FeedStatus> getFeeds();
}
