/**
 * The server: watches the configured roots and loads the files that appear in
 * the feeds below them.
 *
 * <h2>What this package offers, and why it is six types</h2>
 *
 * This is an application, not a library, so the surface is only what someone
 * outside actually holds. Two audiences hold something.
 * <p>
 * An <strong>embedder</strong> builds a {@code
 * io.github.ralfspoeth.xldr.server.Config}, supplies a {@link
 * io.github.ralfspoeth.xldr.server.ConnectionSource}, and drives a {@link
 * io.github.ralfspoeth.xldr.server.Watcher}. That is the whole of running this
 * server from other code, and {@code app} - the shipped runner - uses those
 * three and nothing else.
 * <p>
 * A <strong>monitor</strong> reads {@link
 * io.github.ralfspoeth.xldr.server.ServerMXBean} over JMX, which hands out
 * {@link io.github.ralfspoeth.xldr.server.FeedStatus} values carrying a {@link
 * io.github.ralfspoeth.xldr.server.FeedState}. Those three are public because
 * the MXBean framework requires it: the management interface has to be public
 * to be introspected, and a record is mapped to {@code CompositeData} by its
 * components and rebuilt through its canonical constructor, which a client-side
 * proxy needs in order to hand the value back as this type.
 * <p>
 * Everything else - the registry, the feeds, the deliveries, the sentinel
 * convention, the free-name rule, the class that answers the MXBean - is how
 * this package does its work and is package-private.
 *
 * <h2>The rule, because it was broken twice</h2>
 *
 * <strong>A type is not made public so that it can be tested.</strong> Until
 * 0.38 the tests of this project were a module of their own, in a {@code .test}
 * package, and widening a type was the only way to reach it; two types were
 * widened on that basis and both said so in their documentation. The tests moved
 * into the modules they test at 0.38 and the reason expired, but the {@code
 * public} keywords and the paragraphs justifying them stayed until 0.51, by
 * which time one of the paragraphs was describing a package-private type that
 * had become public and had not noticed.
 * <p>
 * A test in this package reaches everything in it. If a test cannot reach what
 * it needs, the test is in the wrong package.
 *
 * @author Ralf Spöth
 */
package io.github.ralfspoeth.xldr.server;
