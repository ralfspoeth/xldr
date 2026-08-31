/**
 * The input adapter SPI: what a format module implements, and what the loader
 * may then assume of it.
 *
 * <h2>The shape of it</h2>
 *
 * A format module provides one {@link
 * io.github.ralfspoeth.xldr.ia.InputAdapterFactory} through {@code
 * ServiceLoader}, declared in its {@code module-info.java}. The factory is asked
 * {@link io.github.ralfspoeth.xldr.ia.InputAdapterFactory#reads(String) whether
 * it reads} a MIME type, and if so builds an {@link
 * io.github.ralfspoeth.xldr.ia.InputAdapter} from the {@link
 * io.github.ralfspoeth.xldr.spec.InputSpec}. The loader then calls {@link
 * io.github.ralfspoeth.xldr.ia.InputAdapter#parse} once per record mapping, with
 * a freshly opened stream each time, and binds the values by name.
 *
 * <h2>What an adapter needs</h2>
 *
 * Five types from this package - {@code InputAdapterFactory}, {@code
 * InputAdapter}, {@code Result}, {@code Row}, {@code Field} - plus {@link
 * io.github.ralfspoeth.xldr.ia.Formats}, which is the whole of it: an adapter
 * that needs a seventh type from here has found a gap in the SPI rather than a
 * missing import. And seven from {@code spec}: {@link
 * io.github.ralfspoeth.xldr.spec.InputSpec}, {@link
 * io.github.ralfspoeth.xldr.spec.RecordSelectorSpec}, {@link
 * io.github.ralfspoeth.xldr.spec.FieldSelectorSpec}, {@link
 * io.github.ralfspoeth.xldr.spec.Locator}, {@link
 * io.github.ralfspoeth.xldr.spec.Selector}, {@link
 * io.github.ralfspoeth.xldr.spec.Discriminator} and {@link
 * io.github.ralfspoeth.xldr.spec.DataType}. The rest of {@code spec} - {@code
 * MappingSpec}, {@code RecordMappingSpec}, {@code ValueSource}, {@code VarSpec} -
 * is the loader's half of a spec and no business of an adapter's.
 *
 * <h2>The obligations</h2>
 *
 * These hold for every adapter in this project and are what the loader, {@code
 * xldr check} and the mapping author rely on. Each is checked by the {@code tck}
 * module; the list is written out here because a test tells you that something
 * failed and not why it was ever required.
 *
 * <ol>
 *   <li><b>Refuse at construction what the spec already proves wrong.</b>
 *       {@code createInputAdapter} is the last moment before a file exists, so a
 *       {@link io.github.ralfspoeth.xldr.spec.Locator} case the format cannot
 *       mean, an {@code nth} where there is nothing to count, a malformed
 *       selector or a pattern that will not compile belongs there rather than at
 *       four in the morning halfway through a load. Name what the author wrote,
 *       not what they left out.</li>
 *
 *   <li><b>Honour the declared type.</b> A {@link
 *       io.github.ralfspoeth.xldr.spec.FieldSelectorSpec#dataType()} of {@code
 *       null} means {@code TEXT}; anything else means the value arrives as that
 *       type, and the {@link io.github.ralfspoeth.xldr.ia.Field} says so with
 *       {@link io.github.ralfspoeth.xldr.spec.DataType#clazz()}. Use {@link
 *       io.github.ralfspoeth.xldr.ia.Formats#of} over the spec's properties and
 *       {@link io.github.ralfspoeth.xldr.ia.Formats#parse} for the conversion, so
 *       that {@code dateFormat}, {@code numberFormat} and {@code locale} mean the
 *       same thing in every format. An adapter that returns text whatever the
 *       spec declared hands the loader a {@code String} for a numeric column, and
 *       nothing says so until the insert.</li>
 *
 *   <li><b>Refuse a record selector the spec did not declare.</b> {@code parse}
 *       is called with a name; one that is not there is a typo in a mapping, and
 *       the adapter is the only place it can surface. Say which names are
 *       declared.</li>
 *
 *   <li><b>Refuse a field selector the record selector has not got</b> - unless
 *       the format names its own fields, as a headed CSV does, where a column of
 *       the header is an implicit {@code TEXT} field and needs no declaring.</li>
 *
 *   <li><b>Expose exactly the fields asked for.</b> {@code Result.fields()} holds
 *       one {@code Field} per requested name and no others. Their order is the
 *       adapter's business; the loader binds by name.</li>
 *
 *   <li><b>An absent value is {@code null}.</b> A record that has no such
 *       component, a pattern that does not match, a line that stops short: {@link
 *       io.github.ralfspoeth.xldr.ia.Row#get(String)} answers {@code null} and the loader
 *       binds SQL NULL. Where a format can tell <em>empty</em> from <em>absent</em>
 *       it may keep the difference and should say so - the XML adapter returns the
 *       empty string for an element that is there and empty, because XPath cannot
 *       distinguish the two, and the SWIFT adapter does the same for a delimiter
 *       tag, because it can.</li>
 *
 *   <li><b>Say what is wrong with the record, not just that something is.</b> A
 *       failure mid-stream should name the record it happened at; the loader adds
 *       the record selector and the table.</li>
 *
 *   <li><b>Read the source once, lazily, and leave it to the caller to close.</b>
 *       {@link io.github.ralfspoeth.xldr.ia.Result#rows()} is a stream the caller
 *       closes. The loader opens a fresh source per record mapping, so an adapter
 *       need not rewind and must not assume it may.</li>
 *
 *   <li><b>Hold no mutable state across calls.</b> A factory is a singleton the
 *       service loader hands out; an adapter serves every record selector of one
 *       spec and may be asked for them in any order. Everything an adapter needs
 *       is in the spec it was built from.</li>
 *
 *   <li><b>Ignore a property you do not recognise.</b> Settings are per format and
 *       a spec may be read by more than one; a name this adapter has no use for is
 *       not an error.</li>
 * </ol>
 *
 * <h2>Checking an implementation against this</h2>
 *
 * <strong>The kit is the contract; this list is why.</strong> Extend {@code
 * InputAdapterContract} in the {@code tck} module, supply a factory, a MIME type,
 * a spec and a sample, and every obligation above becomes a named test.
 * <p>
 * Seven of the ten need nothing from you but those four things. Three do, because
 * no kit can invent a spec your format cannot mean, a record with a value
 * missing, or a record that is broken: for those the kit supplies the checking
 * and asks you for the evidence, through {@code refusals()}, {@code absences()}
 * and {@code breakages()}. The first of those three is abstract, so an adapter
 * that has never been asked what it refuses does not compile; the other two
 * default to empty and skip, saying in the report which obligation went
 * unchecked.
 * <p>
 * That arrangement replaced a plainer one at 0.51, where four of the ten were
 * "yours to test" and the prose had already drifted from the kit - it named an
 * obligation as unchecked that the kit had been checking since the day it
 * shipped. Two statements of one contract is one too many, for the reason two
 * readings of the {@code header} setting were.
 * <p>
 * A green run says an adapter keeps the seven, and keeps the other three on the
 * evidence its author supplied. It does not say the adapter is right, and no kit
 * could.
 *
 * <h2>Where the examples are</h2>
 *
 * Five adapters ship with the toolkit - {@code csv}, {@code flt}, {@code json},
 * {@code xml}, {@code xlsx} - and each is a worked answer to the list above for a
 * different shape of file. {@code flt} is the smallest complete one. A sixth,
 * <a href="https://github.com/ralfspoeth/swift-mt">swift-mt</a>, lives outside
 * this repository and is the one that shows what the list is for: written against
 * the published interface alone, it kept nine of these ten and diverged on the
 * second, because at the time nothing stated it.
 *
 * @author Ralf Spöth
 */
package io.github.ralfspoeth.xldr.ia;
