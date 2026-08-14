package io.github.ralfspoeth.xldr.server;

/**
 * How far along a feed is, as reported over JMX.
 * <p>
 * An enum rather than a boolean because there is a third case already in sight:
 * a feed whose spec will not parse is deregistered outright today and vanishes
 * from the bean, which is the same invisibility this exists to fix, one step
 * further along. When that is given a state it belongs here.
 * <p>
 * The MXBean framework maps an enum to its name, so a client sees
 * {@code "PENDING"} without needing this class.
 */
public enum FeedState {

    /**
     * Both files are there and read: the feed loads what arrives.
     */
    ACTIVE,

    /**
     * A {@value Delivery#FILE} and no mapping spec. The feed is real - its
     * directories exist, it is watched, and its producer may deliver - but
     * nothing is loaded, and what arrives waits in {@code in/} until a spec
     * appears.
     * <p>
     * Worth watching for rather than merely knowing about: it is logged once,
     * when the feed enters this state, so a feed that has been half-configured
     * since Tuesday is not something the log will tell anyone on Thursday.
     */
    PENDING
}
