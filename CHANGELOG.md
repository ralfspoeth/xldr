# Changelog

Notable changes per release. Until `1.0` the API and the mapping-spec format may change in any release, including in
ways that break existing code and existing specs; those changes are listed here under **Breaking**.

The versions are the git tags `xldr-<version>`; the published artifacts carry the same version under the group
`io.github.ralfspoeth.xldr`.

## Unreleased

A release about what the input-adapter SPI actually promises, ahead of `1.0` freezing whatever it says.

There were two answers to "what must an adapter do": ten obligations in `ia`'s package documentation, and six of
them checked by the conformance kit. Two statements of one contract drift, and these had - the prose named an
obligation as unchecked that the kit had been checking since the day it shipped. The kit is now the contract and
the prose is its reasoning.

### Breaking

- **`InputAdapterContract` has a new abstract method.** `refusals()` returns the specs your adapter must refuse at
  `createInputAdapter`, each with a sentence saying what is wrong with it. Abstract rather than defaulted because
  the answer is the point: an adapter written against this SPI once accepted two selector syntaxes it had never
  implemented and returned nulls for them, all the way into the database, and nothing in the kit asked. Return
  `List.of()` where your format proves nothing wrong ahead of time and the check skips, saying so - which is a
  different thing from never having been asked.

  Two further hooks, `absences()` and `breakages()`, default to empty, so only `refusals()` breaks a compile. They
  supply a sample with a value missing and a sample that cannot be read, for the two obligations that were
  previously "yours to test": that an absent value is `null`, and that a failure names the record it happened at.

- **`server` exports six types where it exported twelve.** `Feed`, `Sentinel`, `FreeName`, `ServerStatus`,
  `FeedRegistry` and `Delivery` are package-private. What is left is what someone outside actually holds: an
  embedder builds a `Config`, supplies a `ConnectionSource` and drives a `Watcher`, and a monitor reads
  `ServerMXBean`, which hands out `FeedStatus` values carrying a `FeedState`. Those last three are public because
  the MXBean framework requires it, not because this project chose it.

  Two of the six were public *so that they could be tested*, and both said so. That was true until 0.38, when the
  tests were a module of their own and widening was the only way in; the tests moved into the modules they test and
  the reason expired, but the keyword stayed for thirteen releases. By 0.51 one of the two paragraphs was also
  asserting that `Feed` was package-private, which it was not - the explanation of the surface had drifted from the
  surface, in the file next to it. The package documentation now states the rule: a test that cannot reach what it
  needs is in the wrong package.

  `Delivery` is the one an outside caller did touch, for `Delivery.FILE`. `ServerIT` now writes
  `"delivery.properties"`, which is what an operator types into a directory and what that test is about.

- **`Header` is no longer part of the SPI.** It lived in `ia` because `xldr validate` reasoned about a header
  without ever building an adapter; that command was removed, and with it the rule and the only caller outside the
  CSV module. It is now package-private in `csv`, beside `EmptyLine`, which is the setting it has always described.
  The `header` spec property is unchanged - this is the Java type going, not the setting. The SPI is six exported
  types where it was seven.

### Added

- **Obligations 1, 6 and 7 are checked**, on the evidence the three new hooks supply, so all ten of the package
  documentation's obligations are now either run or explicitly declined. The five shipped adapters each state their
  refusals - between two and six apiece - `csv`, `flt` and `xml` each supply a sample with a value missing, and
  `csv` supplies one that cannot be read.

  The four adapters that supply no breakage skip that check, and the skip is the finding: only `csv` names the
  record in a mid-stream failure today. `flt` says "incomplete final record" and the rest surface whatever the
  underlying parser said, which is obligation 7 unkept. Recorded rather than fixed here, the fix being a change to
  four adapters and not to the kit.

### Fixed

- **The CSV adapter accepted two record selectors of one name**, and loaded whichever came first - silently, for as
  long as the feed ran. The other four key their record selectors by name and refuse a repeat as a side effect of
  building that map; this one keeps the spec and scans it, so there was no such moment. Found by the conformance
  kit on its first run, which is the first time anything had asked all five adapters the same question.

## 0.50

A release about the one thing a spec contributes to a statement that is not bound as a parameter.

Values are bound, so they can be anything. Names are written into the text, so they cannot - and until now only half
of that was enforced. A function name had been held to a shape since 0.40, on the stated grounds that it was "the
only part of a value source that reaches the statement text". A table and a column reach it too: `SqlIdentifier`
was concatenated into every insert and every lookup subquery with nothing but a blank check in front of it, and
both published schemas would have validated the spec that said so.

The mapping-spec format changes, so `mapping-spec-0.50` is published in both formats.

### Breaking

- **A table or column name is held to being a name.** Unquoted it is a letter or underscore followed by letters,
  digits, underscore, `$` or `#`; anything else is written in double quotes, with an interior quote doubled. The
  plain set is the union of what the targets accept unquoted rather than the intersection - `$` and `#` are
  Oracle's, and a letter is any letter because PostgreSQL takes them - so a spec can still name any column that
  exists. What it excludes is everything that could end one token and start another.

  A **qualified** name is refused rather than folded through. It used to work by accident, which made the table name
  and `target.properties` two ways to say where a table lives. Refusing is the reversible direction: allowing one
  later breaks nothing, refusing one later would.

  Every table and column in the tutorial, the fixtures and the tests was checked against the new rule before it went
  in; none of them changes.

- **`SqlIdentifier.folded()` is now `SqlIdentifier.sql()`.** The old name described one of the two things the
  method does: an unquoted name is folded up, and a quoted one is passed through with its quotes, which is no
  folding at all. What both have in common is the thing this release is about - it is the string written into the
  statement rather than bound to it - so the name now says that at the call site. `unquoted()` is unchanged and
  deliberately still not its counterpart: it hands back the spelling the spec used, which is not what a catalog
  holds, and a caller comparing against `DatabaseMetaData` has to fold it in the direction the driver reports.
  `Loader`'s private `normalizeIdentifier` wrapper went with it, having existed only because the old name read
  poorly where SQL is built.

- **The spec model is no longer `Serializable`.** Nothing serialized a spec, so it was a promise made to nobody -
  and a promise that would have had to be kept from 1.0, including the part where adding a record component breaks
  a stream written by an older release.

### Fixed

- **`SqlIdentifier.unquoted()` did not undo the doubling.** `"a""b"` is the single column `a"b`, and `xldr check`
  compared `a""b` against what `DatabaseMetaData` reports - so a column with a quote in its name always read as
  absent. Rare, and wrong in the way that looks like a missing column rather than like a bug.

- **Two malformed names got through.** `""` passed the blank check, being two characters, and produced an empty
  quoted identifier; `"abc` with one quote folded to `"ABC` and became a syntax error on the first load rather than
  a complaint when the spec was read.

### Added

- **`mapping-spec-0.50`**, in both formats, carrying the same rule so that an editor refuses what the reader
  refuses. The two files share one pattern, written out as XML's own `NameStartChar` and `NameChar` ranges plus `$`
  and `#` rather than as `\p{L}`, so that the JSON and XML schemas cannot drift into two dialects of the same idea.

## 0.49

A release about which types a caller is meant to name. A sealed interface can be public with its cases hidden or
public with its cases the point, and the two are different designs; this sorts three of them into the right one and
gives two more the factories that say how a call site should read.

The mapping-spec format is unchanged, so `mapping-spec-0.47` remains its schema and a spec that loaded under 0.48
loads under 0.49.

### Breaking

- **`Delivery`'s two cases are no longer public.** `Delivery.Atomic` and `Delivery.Signalled` are now
  `AtomicDelivery` and `SignalledDelivery`, package-private in `server`, and the interface `permits` them by name.
  Code outside that package can read a `delivery.properties` and ask `claims(file)`, which is the whole of what
  there was to ask; it can no longer name a case, match on one, or construct one.

  Nothing in this repository did. Every use of the two was `FileProcessor` and `DeliveryTest`, both in the same
  package, and the only thing crossing a module boundary was the `Delivery.FILE` constant that `ServerIT` writes.

  It is the opposite of what `spec`'s sealed types do, and deliberately so. `ValueSource` and `Locator` exist
  precisely so a caller can ask which case it is - the loader and every adapter switch over them, and a new case
  becoming a compile error at every site is what that buys. `Delivery` answers a question rather than describing a
  value, so which case answered is nobody's business and the smaller surface is worth having before 1.0 fixes it.
  `Feed` keeps its public cases for a third reason again: `Feed.Active` appears in five public signatures as the
  feed that *has* a spec, so hiding it would trade a compile-time guarantee for a cast.

### Added

- **`Locator.at`, `Locator.where`, `Selector.nth` and `Selector.text`**, joining `Locator.every` which had been
  alone since the type was introduced. The constructors are unchanged and still public; this is how a call site
  spells one, and every one in the repository - 91 of them - now does.

  `every()` was a factory because `new Locator.Every()` reads badly for a record with no components. The other four
  are there so that the three answers to *which records* read as three answers, and so that a field selector's two
  ways to say *where* read as two.

  `Selector`'s two return their case - `Nth` and `Text` - where `Locator`'s three return the interface. The
  difference is that these cases carry something worth asking for afterwards, `Nth.index()` being what an adapter
  addresses with, so a factory that erased the case would make `Selector.nth(1).index()` fail to compile for no
  reason. A locator's cases have no such accessor and lose nothing.

## 0.48

A release about an overload nobody called. A convenience constructor threw away the type it was handed, and the
build was green throughout because every caller in this repository spelled the long form instead.

The mapping-spec format is unchanged, so `mapping-spec-0.47` remains its schema and a spec that loaded under 0.47
loads under 0.48. Nothing here is breaking: the fix makes a constructor keep an argument it was already being
given.

### Fixed

- **`new FieldSelectorSpec(name, nth, dataType)` discarded the type.** The counted convenience constructor passed
  `null` to the canonical one whatever it was given, so a field declared `DECIMAL` or `TEMPORAL` came back untyped,
  was delivered as text, and was bound into a numeric or timestamp column as a string.

  Nothing in the reactor called it - all 151 constructions of a counted field selector spell
  `new Selector.Nth(n)` - so the build was green and stayed green. An overload with no caller is tested by nobody
  and trusted by the next person to find it, which is how this was found: writing a new adapter against it.
  `FieldSelectorSpecTest` now holds both convenience constructors to saying exactly what the canonical one says.

  Its parameter was also called `index`, where `Selector.Nth` counts from one and reserves `index()` for the
  0-based form - the name promised the opposite of what the value had to be. It is `nth` now. The parameter name is
  not part of the signature, so nothing has to change.

## 0.47

A release about one name. The field type `DATE` is called `TEMPORAL`, which is what it has always been.

The mapping-spec format changes, so `mapping-spec-0.47` is published in both formats. **A spec that declares a
`DATE` field has to be changed**, and is refused with that instruction until it is - it is one word per field
selector, and the reader says which word.

### Breaking

- **`DataType.DATE` is `DataType.TEMPORAL`**, in the Java enum and in the spec format. Nothing about the type's
  behaviour changed: it was already bound to `LocalDateTime`, already registered as JDBC type 93 - `TIMESTAMP`, not
  `DATE` - and already read a plain ISO date as the timestamp at the start of that day.

  Which is the argument. Every other name in this enum says a *kind* rather than a type of some particular system -
  `TEXT`, `INTEGRAL`, `FP`, `DECIMAL` are none of Java's and none of SQL's, deliberately, so that nobody reads `FP`
  as a `float` and infers a width. `DATE` broke that twice over: it was borrowed from SQL, and it was the one SQL
  type this is not, a SQL `DATE` having no time of day. Three separate pieces of javadoc existed only to walk the
  name back - "a date/time field", "ISO-8601 for `DATE` (a plain date as well as a timestamp)", "midnight of that
  day, the timestamp this type declares". When the documentation's job is apologising for the identifier, the
  identifier is the thing to change.

  A spec still saying `DATE` gets told what to write rather than `valueOf`'s "No enum constant", which is true and
  useless. That is the one error message here written with a specific reader in mind: someone whose spec was correct
  one release ago.

### Fixed

- **A type name was folded in the default locale.** The JSON reader upper-cased `"type"` with `String::toUpperCase`
  and no locale, so under a Turkish default `"integral"` became `"İNTEGRAL"` - the dotted capital I - and matched no
  constant. Two of the five names carry an `i`. The XML reader had always used `Locale.ROOT`; both now call
  `DataType.named`, which is the one place a type name is read and the only place left to get this wrong.
  `SqlIdentifier` folds with `Locale.ROOT` for exactly this reason and has since 0.43.

### Documentation

- **Tutorial page 11 says what to change** in a spec written before 0.47, that being the page a reader with an old
  spec will open.

## 0.46

A release about the value that is inside another one. A feed delivering `prices_EUR_20260101.csv` carries its
currency in the name of the file and nowhere else, and until now the only answers were a column in the spec that
lied or a pre-processing step outside it.

The mapping-spec format changes, so `mapping-spec-0.46` is published in both formats. A spec that loaded under 0.44
or 0.45 loads unchanged - the two breaking entries are a Java one, and one that refuses a spec no release could ever
have loaded.

### Breaking

- **`ValueSource` has a sixth case, `Regex`**, so an exhaustive switch over it in embedding code no longer compiles.
  That is the trade the sealed hierarchy was chosen for: a new source is a compile error at every place that decides
  what to do with one, rather than a `default` branch quietly doing the wrong thing on the first spec that uses it.
  Five switches in this repository named it, and one of them - `Check.collectFieldNames` - would have been a silent
  defect had the compiler not asked.

  It holds a compiled `java.util.regex.Pattern` and carries hand-written `equals` and `hashCode` over the pattern's
  text and flags, since two compiles of one string are distinct objects and a record's own equality would call two
  readings of one spec file unequal. `Discriminator.Matches` does the same and for the same reason.

- **A column's `regex` may not read a `lookup`**, and a spec that says so is now refused when it is read. It was
  refused before too, by the loader, while planning the insert - so the spec validated in an editor, deployed, and
  failed on its first file. A regex runs in the JVM on a value bound as a parameter, and a column's lookup is a
  subquery of the insert whose value does not exist until the statement runs; no arrangement of the input could make
  it work, which is what puts the rule in `FieldMappingSpec` beside the one about calls. A **var's** regex may read a
  lookup and always could: that is one query and then a match.

### Added

- **A `regex` value source**, wherever a value source may stand - a field mapping, a var, a lookup's key or
  condition, an argument to an `fn` or a `transform`:

      {"name": "currency",
       "regex": {"pattern": ".*_([A-Z]{3})_.*", "group": 1, "expr": "${xldr.filename}"}}

      <regex pattern=".*_([A-Z]{3})_.*" group="1" expr="${xldr.filename}"/>

  The subject is written on the regex itself, exactly as it would be written on the element holding it, which is
  what makes it any source at all rather than a list of the ones that were thought of. `group` defaults to 0, the
  whole match.

  **No match is NULL**, as is a subject that is null - the answer a lookup already gives for a key matching no row,
  and for the same reason: one file whose name does not fit should not fail a delivery of a hundred thousand
  records. The gap is in the data, where it can be reported on.

  **The pattern is compiled when the spec is read**, so a pattern that does not compile, or a `group` the pattern
  does not capture, is refused there rather than on the first delivery - a feed is activated only if its patterns
  compile. It was first tried as a `${group(...)}` built-in of the expression language, which does not work: the
  hole scanner takes the first `}`, so `${group(x, '[A-Z]{3}', 1)}` ends inside the quantifier. A pattern wants a
  place of its own where nothing else is looking for braces.

- **`mapping-spec-0.46`**, in both formats, for `regex`. Two definitions as always, a row one and a var one: the var
  flavour reads no `fieldSelector` and the row flavour reads no `lookup`, which are the two rules above stated where
  an editor can enforce them.

  The XSD also stops accepting a var `lookup` that writes both an `<fn>` key and a `<conditions>` child. It was a
  sequence of optional children and is now a choice, for `<regex>`'s sake; the combination it used to allow was the
  one place in this format where the reader took a document, dropped half of it, and said nothing.

### Fixed

- **`xldr check --sample` resolved no field a `regex` reads.** The set of fields the adapter is asked for is built by
  walking each source, and the `Regex` case returned nothing instead of recursing into its subject - so the preview
  read the sample without that field and showed a spec behaving differently from the way the loader would run it.
  The one thing that command must not do. `Check`'s two other walks, over calls and over the rendering, had the same
  gap.

### Documentation

- **Tutorial page 7 gains a section on taking part of a value**, rather than a fourteenth page. A `regex` is most
  often reached for while writing a variable - the currency in the name of the file is the case - so it belongs
  where a reader already is, next to the table of what a variable may be.

## 0.45

A release about two things nothing was checking. A spec's calls on the database are now looked up before a deploy
rather than discovered by the load, and the release gate itself says on every build that it is still able to do its
job - three skipped tests having been indistinguishable from three broken ones.

The mapping-spec format is unchanged, so `mapping-spec-0.44` remains its schema and a spec that loaded under 0.44
loads under 0.45. The one breaking entry is a Java one and touches an embedder reading a `Target` apart.

### Breaking

- **`Target`'s catalog and schema are `SqlIdentifier`s**, where they were strings. They are folded the way every
  other name that reaches SQL is, so a deployment whose `target.properties` says `Staging` names the schema one
  saying `STAGING` names, and two such targets are now equal. A quoted name stays exact.

  Constructing one from text is unchanged - `new Target("warehouse", "staging")` still compiles and is what
  `target.properties` and the servlet both use - so only code reading `catalog()` or `schema()` back has to move.
  The blank check stays on `Target` rather than being left to the type: a blank setting is a half-deleted line, not
  a mistake about identifiers, and "leave it out" is the instruction that helps.

  It closes the last place an identifier lived as a string. `Loader` had been wrapping these two at the point of
  use, which worked and put the knowledge in the wrong place.

### Added

- **`xldr check` verifies that the functions and procedures a spec calls exist**, which it has never done while
  checking a lookup's table and columns since it was written. A misspelled `fn` or `transform` name was found by
  the load, with the feed deployed.

  It reports a missing routine only where an absence means something, because a check that blocks a correct
  deployment is worse than one that misses a typo. A **qualified** name is listed as unchecked - `pkg_load.next_id`
  is a schema-qualified function in PostgreSQL and a package member in Oracle, and `getFunctions` cannot tell them
  apart - and a driver whose metadata lists no routines at all is called unusable rather than read as an empty
  database. Functions and procedures are looked for in one set, since which of the two a product reports a routine
  under is its own business: H2 lists an alias among the procedures whatever it is called with.

- **`ReleaseReadinessTest` has a test that always runs**, checking the gate rather than the release. The other
  three are skipped on every ordinary build, which is right - `## Unreleased` and a lagging BOM version are both
  correct while the version is a snapshot - and is also indistinguishable from three tests that have quietly
  stopped working.

  The gap worth closing was not a regex failing, which already throws before the assumption is reached, but a
  `<revision>` that merely resembles a snapshot: a property left behind after a move off CI-friendly versions would
  skip the gate forever while releases went out unchecked. It now insists the value is shaped like a version or a
  snapshot of one, and reads the other two inputs as well, so a pattern that stops matching fails on the next
  ordinary build rather than on the day of a release.

## 0.44

A release about a restriction that protected against nothing. A lookup may match on no column at all, which is how
a single-row view or Oracle's `dual` is read; 0.43 refused it on a hazard it tolerates everywhere else.

Saying it needs an empty condition list, and in XML an empty list needs somewhere to be empty - so the conditions
moved into a `<conditions>` wrapper. That is the one breaking entry, and it touches XML specs written against 0.43
and nothing else. `mapping-spec-0.44` is published for it, with `mapping-spec-0.43` frozen as that release shipped
it.

### Breaking

- **An XML lookup's conditions moved into a `<conditions>` wrapper.** A spec written against 0.43 with
  `<condition>` children directly under the `<lookup>` is refused; wrap them. The JSON spelling is unchanged, and
  the single-column `keyColumn` spelling is unchanged in both.

  The wrapper is what makes `<conditions/>` expressible, and that is the point of it: with repeated children, an
  empty list and a forgotten key are the same document, so one of the two would have had to be unsayable.

### Added

- **A lookup may match on no column at all**, which reads a single-row view or Oracle's `dual`:

      "lookup": { "table": "current_rate", "column": "factor", "conditions": [] }
      <lookup table="current_rate" column="factor"><conditions/></lookup>

  0.43 refused this, and the refusal protected against nothing. The argument for it was that such a query takes an
  arbitrary row where the table has several - which is exactly what a keyed lookup does when its key matches
  several rows, and that has never been refused. A rule that forbids one spelling of a hazard while allowing
  another is not a rule.

  It has to be said explicitly. A lookup with neither `keyColumn` nor `conditions` is still refused, so that
  forgetting a key stays an error rather than quietly becoming an unconditional read that stamps one arbitrary row
  onto every record - which is a wrong value in every row and no complaint anywhere.

- **`mapping-spec-0.44`**, in both formats. `mapping-spec-0.43` is frozen exactly as 0.43 published it: it
  describes the format that release reads, and a spec pinned to it keeps validating.

## 0.43

A release about identifiers. A lookup may match on more than one column, which is what a reference table keyed by a
currency and a date has always needed; and the names a spec writes for tables and columns become a type of their
own, so that a collection can hold one column once rather than being scanned for the two spellings of it.

The two go together. Keying a lookup's conditions by `SqlIdentifier` is what makes matching twice on one column
unrepresentable, where the first draft of the composite key had to look for the collision and refuse it.

The mapping-spec format changes for `conditions`, so `mapping-spec-0.43` is published in both formats. The one
breaking entry is a Java one: a spec file says exactly what it said before, and the single-column spelling is
untouched.

### Breaking

- **`SqlIdentifier` is a record, and the spec model's table and column names are of that type.** It was a static
  helper with a `folded` method; it is now `record SqlIdentifier(String name)` whose `equals` and `hashCode` compare
  as a database would, so two instances are equal when the database would resolve them to one column. Quoted names
  stay exact, being case-sensitive by definition.

  `FieldMappingSpec.column`, `RecordMappingSpec.table`, `ValueSource.Lookup`'s `table` and `column` and the keys of
  its `conditions` all carry it. Each of those records keeps a constructor taking the names as text, so the code
  that builds a spec by hand mostly does not change; what changes is what comes back out of an accessor, and any
  record pattern that destructured one.

  The point is what a collection keyed by it can no longer hold. Two conditions on one column and two field
  mappings onto one column were both scans looking for a folded collision and throwing; both are now impossible to
  represent, and `RecordMappingSpec`'s check is a plain `Set.add`. Where a spec author needs to hear about it - a
  file naming a column twice - the readers report it, their `put` returning the previous value, and that now
  catches `ccy` beside `CCY` as well as `ccy` beside `ccy`.

  Equality therefore ignores part of the record's state, which is worth knowing: a map keyed by these keeps the
  spelling put in first, and that is the one a message or `xldr check` shows. The precedent is
  `Discriminator.Matches`, which compares on `pattern.pattern()` for the same reason.

  The mapping-spec format is untouched - this is a Java type, and a spec file says exactly what it said before.

### Added

- **A lookup may match on more than one column.** Where a reference table is keyed by two - a rate by currency and
  date, a price by article and price list - `keyColumn` becomes `conditions`, one entry per column:

      "lookup": { "table": "rate", "column": "factor", "conditions": [
                    { "column": "ccy",  "fieldSelector": "currency" },
                    { "column": "asof", "var": "valueDate" } ] }

  They are `and`ed, and each takes the same sources a single key takes. `keyColumn` beside a source stays the
  one-condition spelling and is unchanged: a lookup on one column reads better said once than wrapped in an array
  of one. A lookup writes one form or the other, and a spec writing both is refused rather than picked between.

  `ValueSource.Lookup` carries them as a `SequencedMap<String, ValueSource>`, not a `Map`. The order is the order
  of the `where` clause and therefore of the bound parameters, and `Map.copyOf` randomises its iteration order per
  JVM run - the same spec would have produced different SQL, a different statement-cache key and different `check`
  output on different days. Two conditions on one column cannot be expressed at all, the map being keyed by
  `SqlIdentifier`, for which `ccy` and `CCY` are one key.

- **`mapping-spec-0.43`**, in both formats, for `conditions`. Both lookup flavours gain it and keep their separate
  definitions, so a var's condition may no more read a field than a var's key ever could.

### Fixed

- **A var's lookup may be keyed by an `fn`, which the schemas have claimed since 0.40 and the reader refused.** An
  editor passed such a spec and the server then would not load it, throwing "needs exactly one of fieldSelector,
  constant, var, expr". The reader now does what the published schemas describe.

  It slipped in when the `varLookup` definition was written for 0.40: `fn` was added to it for symmetry with a
  var's own sources, and nothing checked that `SpecNode.source()` could produce one. Both paired schema tests
  missed it for three releases because no fixture ever keyed a lookup that way - a reminder that those tests prove
  agreement only about the shapes they exercise. A call in a *column* lookup is still refused, one call per row
  being what `FieldMappingSpec` exists to prevent.

## 0.42

A release about what happens once the records are in. A spec may end with `transform`, a list of procedures the
target database runs after the last record and before the commit - closing a batch, reconciling what arrived. It is
the counterpart of the `fn` added in 0.40 and deliberately not the same thing: a function hands back a value and
belongs to a var, a procedure hands back nothing and belongs to the spec.

The mapping-spec format changes, so `mapping-spec-0.42` is published in both formats. Two entries are breaking and
both are for an embedder: a spec read from a file needs no edit, `transform` being optional and absent from every
spec written before this release.

### Added

- **`transform`: procedures called after the load**, a top-level list in both formats, each entry a `name` and its
  `args`:

      "transform": [ { "name": "pkg_load.close_batch",
                       "args": [ { "var": "batch" }, { "expr": "${xldr.rowsLoaded}" } ] } ]

  They run in the order written, on the load's own connection, after the last record and **before the commit**. A
  procedure therefore sees the rows this load inserted while nobody else can, and one that throws rolls the whole
  file back. That is the point of the placement: a load that half-happened would be two outcomes where the loader
  has always promised one, and there would be nothing safe to retry.

  `ProcedureCall` is deliberately **not** a `ValueSource`. A function yields a value and so belongs wherever a value
  belongs; a procedure yields nothing, and putting it in that hierarchy would add a case every exhaustive switch has
  to name and then discard. It carries no return type for the same reason - no OUT parameter to register, and
  `{call name(?)}` is the whole statement. A spec that wants a value from the database wants a var with an `fn`.

  Arguments are the sources a var may have, and a `fieldSelector` among them is refused at any depth: the records
  are gone by the time a transform runs. That is the rule `VarSpec` already enforced at the other end of the load,
  and the two now share one implementation rather than two that resemble each other.

- **`${xldr.rowsLoaded}`**, the number of rows the load inserted, available to a transform's arguments and nowhere
  else. It is the first ambient value the loader supplies rather than the application - nobody can pass the count in
  beforehand - and in a field mapping it stays an unknown name, mid-file there being no such number.

- **`mapping-spec-0.42`**, in both formats, for `transform`. The XSD puts it after the mappings, which the reader
  does not require; that is the one place the two disagree on purpose, since a transform runs after the load and a
  spec that writes it first says something it does not mean.

  It was briefly committed as `mapping-spec-0.41`, which was wrong twice over: 0.41 changed no format and keeps
  `mapping-spec-0.40`, and a 0.41 server ignores an unrecognised `transform` in silence - so an author validating
  against a file named for their own release would have been told their spec was fine and then watched the
  procedure never run. Named after the release that changed the format, as the rule says.

- **`ReleaseReadinessTest`**, which fails the build if the changelog's newest section or the README's BOM version
  is not the version being released. It reads `<revision>` from the root pom - the property `release:prepare`
  rewrites before it runs `clean verify` and long before it tags - so it is skipped for every snapshot build, when
  `## Unreleased` is the right heading, and fires at the one moment the two files can still be fixed.

  Three releases went out wrong this way: 0.28's work under a `## 0.27` heading, 0.33 tagged with `## Unreleased`
  and a stale BOM, and 0.41 repeating both. The release plugin edits neither file and nothing else was looking. It
  belongs beside `TutorialTest` for the reason that one exists: a document nothing checks is a document that drifts.

- **`TransformIT`**, which is where the two claims above are actually checked. Both are claims about a transaction,
  so nothing short of a database can hold the loader to them: the procedure counts the rows it can see and writes
  the number down, and a second test lets one throw and finds the table empty.

### Breaking

- **`MappingSpec` has a third component**, `transforms`, so code constructing one positionally in Java no longer
  compiles. A two-argument constructor is kept for the spec that loads and does nothing afterwards, which is nearly
  all of them, so most call sites need no edit at all. Nothing changes for a spec read from a file: `transform` is
  optional in both formats and absent from every spec written before 0.42.

- **`Loader.close()` runs the transforms** before it commits, which makes it do more than its name says. The
  alternative was worse: `Loader.load` drives the whole sequence but is not the only caller - `xlet` and the
  integration tests build a loader and call `loadInput` themselves - and on that path a spec carrying transforms
  would have done nothing whatsoever, silently. `close()` is the one place every caller reaches and the only one
  that knows the load finished.

## 0.41

A release about names. The five input adapters move under `ia-impl/`, a pom module that becomes their Maven parent,
and the SPI they implement is renamed from `ia` to `ia-def` so that the definition and its implementations read as a
pair wherever they appear together.

The mapping-spec format is unchanged, so `mapping-spec-0.40` remains its schema and a spec that loaded under 0.40
loads under 0.41. The adapters' own coordinates are unchanged too; the one breaking entry is the SPI's.

### Breaking

- **The SPI artifact is `ia-def`, where it was `ia`.** `io.github.ralfspoeth.xldr:ia` no longer exists, so a pom
  naming it fails to resolve rather than quietly resolving to the last version published under that name. Anyone
  importing the `bom` gets the new coordinate with nothing to do; anyone naming the artifact directly - an adapter
  author, which is who the SPI is for - has one line to change.

  **The Java module and the package are unchanged.** Both are still `io.github.ralfspoeth.xldr.ia`: every adapter
  in existence `requires` that name and imports from it, and none of them is improved by editing it. So the artifact
  and the module name differ for this one module, deliberately, and the README says so where it lists the modules.

  The rename is for the pair. With the implementations gathered under `ia-impl`, calling the definition `ia-def`
  makes the relationship legible in the reactor, in the directory listing and in a dependency block - which the
  three-letter `ia` beside a five-module directory did not.

### Changed

- **The five input adapters live under `ia-impl/`**, a pom module that is now their Maven parent, where they used
  to be five directories at the top of the reactor beside `spec`, `ldr` and the rest. Nothing about the artifacts
  changes: an adapter is still `io.github.ralfspoeth.xldr:csv`, `:xml`, `:xlsx`, `:flt`, `:json`, at the same
  coordinates and the same versions, so no consumer has anything to edit.

  They are the one set of modules in this build that a deployment chooses *between* - which of them are on the
  module path is which formats the server reads, and `modules/` in the distribution holds exactly these five. That
  was already true of the code and true of the assembly, and the reactor was the only place it did not show.

  Having a parent is what makes the grouping worth more than a directory: `ia-def`, `jspecify` and
  `junit-jupiter-api` are declared once in `ia-impl/pom.xml`, so each adapter's own pom now says only what makes it
  that format - POI for `xlsx`, Greyson for `json`, and nothing at all for `csv`, `flt` and `xml`, which need no
  library to read what they read. `ia-impl` is published for the same reason the parent pom is: Maven has to be
  able to resolve it to read theirs.

### Fixed

- **`json` never declared its dependency on `jspecify`.** Its `module-info` says `requires static org.jspecify` and
  it compiled anyway, because the SPI brought the jar along transitively - which is one upstream change away from
  not being true. Declaring the three shared dependencies in `ia-impl` is what surfaced it; all five now state it.

## 0.40

A release about reaching into the target database from a spec. A var may now call a function there - a sequence, a
batch opener, a run id - which is the one thing a spec could not get at before except through a `lookup`, and the
reason it took this long is that letting a spec call something is one short step from letting it carry SQL, which it
never will.

The mapping-spec format changes for the first time since 0.35, so `mapping-spec-0.40` is published in both formats.
Beyond the new source it splits the value sources into two definitions - what a column may say and what a var may
say - which is what makes the new rule statable and closes one the old pair had wrong by omission.

Two entries are breaking, and both refuse specs that were readable yesterday. Neither refuses one that ever loaded:
each was a spec that read cleanly, deployed, and threw from the loader on the first file. Moving the refusal to the
document is the whole point - nothing about the input could have made either work.

### Breaking

- **A var may not read a field, at any depth.** `VarSpec` refuses a `fieldSelector` as its source, as a `lookup`'s
  key, and as an argument to a call. The rule was in the class documentation and in no code: the two readers checked
  the top level only, so a field buried in a lookup key slipped past both and failed at load. A var is evaluated
  once before the first record is read, so there is no record for it to read a field from, and no file it could ever
  be given that would change that.

- **`ValueSource` has a fifth case**, so an exhaustive switch over it in code outside this repository no longer
  compiles. That is the sealed hierarchy working: a call is neither a constant nor a lookup, and every place that
  takes a value source apart has to say what it does with one. The two inside the repository that needed it -
  `Check`'s description and the tutorial harness's field collection - are updated.

- **A column may not call a function**, the mirror rule, enforced by `FieldMappingSpec` at any depth. Breaking only
  in the sense the sealed interface is: no spec written before this release contains a call.

### Added

- **`ValueSource.FunctionCall`**, a call on a function in the target database: a `name`, the `type` it returns, and
  `args`, each of which is a value source in its own right - so an argument may be a constant, a var, an expression,
  a lookup, or another call.

      "vars": [ {"name": "loadId", "fn": {"name": "pkg_load.next_id", "type": "INTEGRAL",
                                          "args": [{"constant": "funds"}]}} ]

  It is a var source and nothing else. A var is evaluated once per load and a column is bound once per record, so
  the same call in a field mapping would be a round trip a row - against a value that a sequence or a batch number
  is drawn once by nature. A column reaches the result the way it reaches any var.

  The loader prepares it as JDBC's `{? = call name(?)}` escape through a `CallableStatement` and binds every
  argument, which is why `type` is required where a field selector's may be left out: the OUT parameter is
  registered before the call is made, so there is nothing left to infer it from.

  `name` is one or more identifiers separated by dots and nothing else, checked when the spec is read. It is the
  only part of a value source that reaches the text of a statement - everything a spec otherwise contributes goes in
  bound - so it is held to being a name. A spec with a call in it therefore depends on the target **schema**, in
  that the function has to be there, exactly as a `lookup` already depends on a table being there. It depends on no
  **dialect**: `prepareCall` would not accept raw SQL and no spec carries any.

- **`mapping-spec-0.40`**, in both formats. It adds `fn` and `arg`, and separates a column's sources from a var's,
  which is how both formats can now say that a column has no `fn` and a var no `fieldSelector` - the second being
  something the XSD can state as readily as the JSON schema, since it is a matter of which members each place
  declares rather than of counting them.

- **`DataType.sqlType()`**, the `java.sql.Types` constant each of the five registers an OUT parameter as. It is
  carried as the literal int rather than the named constant so that `spec` needs nothing of `java.sql`: a mapping
  spec is a document, and the module that models one should not require a driver to be on the module path.

- **`xldr check` prints a call**, arguments and return type included, in the same column form as every other source.
  It does not verify that the function exists, where it does verify a lookup's table and columns - the metadata is
  there to do it with, and this is the obvious next thing for the command.

- **A tutorial page on calling a function**, [page 10](docs/tutorial/10-calling-a-function.md), after expressions
  and before types - which is where it belongs, since the page it answers is the one that explains why `nextval` is
  a counter in memory and therefore the wrong thing for a key. The four pages after it are renumbered.

- **`FunctionCallIT`**, which is the only test that exercises the call at all: `prepareCall`, the escape, the OUT
  parameter and the argument indices are the driver's, and nothing in `spec` or `ldr` reaches them. It calls H2's
  built-in `FORMATDATETIME` with two arguments - a var holding `${now()}` and a constant pattern - so both kinds of
  argument and the `Instant`-as-`OffsetDateTime` binding are in one load.

### Removed

- **The Oracle driver is out of the distribution**, and out of the build that produces it. `ojdbc17` was a
  `provided` dependency of `app` and an include in the assembly's `drivers/` set; both are gone, and `drivers/`
  now holds H2 and PostgreSQL, which are the two that are ours to ship.

  It was never a dependency in any real sense - nothing names a driver in code, `java.sql` finds one by service
  binding - so it bought a reader one download and cost the project a licence it had taken on. The release workflow
  had been deleting the jar since 0.33 for exactly that reason, which meant the download and a local build differed
  in one respect that had to be explained in the README, in the release notes and in a generated `drivers/README.txt`.
  Taking it out of the build instead makes all three sentences unnecessary: what you download is what you would have
  built.

  What replaces it is `app/src/dist/drivers/README.txt`, a real file in the tree rather than a heredoc in the
  workflow, saying that installing a driver is copying its jar into that directory - `ojdbc17` from Maven Central
  for Oracle, and the same operation with a different jar for anything else. The workflow keeps a check that fails
  the release if an Oracle driver ever appears in the archive again, since the thing worth guarding is the licence
  and not this one artifact.

### Changed

- **A lookup and a call may both return null.** A lookup whose key matched no row used to throw from a var, which
  contradicted `lookup`'s own documentation of that case; the `requireNonNull` responsible had made the documented
  branch unreachable since it was written. A function that answers "no such thing" by returning nothing is saying
  something a loader has no business overruling either, so a null from either binds a SQL NULL.

### Fixed

- **The 0.35 schemas let a var's lookup be keyed by a field.** One `lookup` definition served both a column and a
  var, so an editor called such a spec valid and the load then threw. The 0.40 pair has two, and `XsdTest` and
  `JsonSchemaTest` now assert each rule against the schema and the reader both - neither of which catches the
  other's cases, an editor never running the reader and a deployed spec never having been put through the schema.

## 0.39

A release about what an adapter owes its caller. The input adapter SPI's contract is written down for the first
time, and `tck` - a new published module - makes six of its ten obligations executable, so an implementation can be
run against them rather than compared by eye against five examples. The server MXBean also gains the gauge that
tells a watcher which has stopped looking from one with nothing to do.

The mapping-spec format is unchanged, so `mapping-spec-0.35` remains its schema and a spec that loaded under 0.38
loads under 0.39. The one breaking entry touches an embedder who builds `ServerStatus` themselves and nobody else.

### Breaking

- **`ServerStatus`'s constructor and `register` take a third argument**, a `LongSupplier` for the watcher's sweep
  count. Only an embedder building the bean itself is affected; `Watcher` passes its own counter.

### Added

- **`Reconciliations` on the server MXBean**: how many sweeps the watcher has attempted since it started, one per
  scan interval and one at startup. It is the liveness gauge the bean was missing - every other number on it also
  stops moving on a quiet morning, so nothing there could tell a watcher that had stopped looking from one with
  nothing to do. A sweep that threw still counts, the question being whether the server looked rather than whether
  it liked what it found.

  It came out of the integration tests, which is worth admitting: four of them asserted that *nothing* happened,
  and an absence cannot be awaited, so each slept three seconds - twelve seconds of the suite spent proving a
  negative by the clock, with a number that was a guess and would have had to grow if the scan interval ever did.
  They now wait for two sweeps and assert against those. The gauge earns its place on the bean independently, but
  the tests are what noticed it was missing.

- **The adapter SPI's contract is written down**, in the package documentation of `io.github.ralfspoeth.xldr.ia`:
  ten obligations, each with the reason it exists, and the surface an adapter actually needs - six types from `ia`
  and seven from `spec`, the rest of `spec` being the loader's half and none of an adapter's business.

  It was written because an adapter outside this repository got one of them wrong. `swift-mt` implements the SPI,
  resolves the published bom, and sees only what is exported; it kept nine of the ten and returned text for every
  field whatever the spec declared - not by oversight but on an argument its README set out, which nothing in the
  interface contradicted. Five worked examples inside one repository are not a contract, and an implementer reading
  the interface alone had nothing else to go on.

- **A conformance kit, `tck`**, published beside the rest. An adapter author extends `InputAdapterContract`,
  supplies a factory, a MIME type, a spec and a sample, and gets one named test per obligation that can be checked
  without knowing the format: that it claims its own MIME type and not everything, that an unrecognised property is
  ignored and changes nothing, that an undeclared record selector and an undeclared field selector are refused,
  that the requested fields and no others come back, that each reports the declared type, that each value is of its
  field's type, and that reading twice gives the same answer.

  Four of the ten stay untestable generically and the kit says so rather than implying coverage it has not got:
  refusing at construction depends on what a format cannot mean, naming the bad record depends on what a bad record
  looks like, empty-versus-absent is a property of the format, and statelessness is sampled rather than proved.
  A green run says an adapter keeps the obligations that can be stated generically, and no more than that.

  It takes `junit-jupiter-api` at compile scope, which is unusual for a published artifact and is the price of the
  shape: the kit is a test class, so an implementer inherits its methods and runs them with their own runner. A
  findings-list API would have avoided the dependency and lost the thing worth having, which is one named failure
  per obligation sitting in the report beside the implementer's own tests.

  The five in-reactor adapters are run against it from `it`, one conformance test each. They are there rather than
  in each adapter's own module because those modules' tests are patched in and so have no descriptor in which to
  require the kit - the same constraint that keeps `xlet`'s test module alive.

## 0.38

A release about where test code lives. Every module but `xlet` and `it` now has its tests in the package they test
and no test `module-info.java`, so a class need not be public merely to be reachable from a test - and the tests
themselves stopped being public, twenty classes and 178 methods of them. Nothing a consumer can see has changed
shape.

The mapping-spec format is untouched, so `mapping-spec-0.35` remains its schema and a spec that loaded under 0.37
loads under 0.38. The one entry under **Fixed** is a README that had been denying, for five releases, that
fixed-length files can carry several record types - which they have been able to do since 0.32.

### Changed

- **`spec`'s tests moved into the packages they test**, and its test `module-info.java` is gone. Surefire patches
  the test classes into the module instead, which is what it does when a module has no test module of its own.
  `DataTypeTest` and `SelectorTest` sit in `spec`; the six reader tests and the `Streams` helper in `spec.io`.

  No production type became less visible - every public type in `spec` is used by another module already, and
  `SpecNode`, the one used nowhere outside its own module, was package-private the whole time. The point is the
  pressure rather than the present state: with a test in a `.test` package of its own, the cheapest way to make
  anything testable was to make it public, and that is how an API surface grows by accident before a 1.0 freezes
  it. A test beside its subject can reach a package-private member and nobody has to decide anything.

  The test surface itself did shrink, once the moves were done: **twenty test classes and 178 test methods are
  package-private now**, which is all JUnit 5 has ever required and all that being in a separate module was
  preventing. `public` on a test says the same thing as `public` on anything else - somebody outside may call this -
  and of a test that is never true. The four fixture helpers, `Streams`, `Feeds`, `Proxies` and
  `AnsweringConnection`, were already package-private with no public members.

  The integration tests went the same way, `*IT` being a surefire-versus-failsafe naming rule rather than a
  category of visibility. One thing stays public on purpose: `XldrServletIT.Deployed`, which is a servlet class an
  embedded container instantiates by name.

  **The five adapters followed**, `csv`, `xml`, `xlsx`, `json` and `flt`, and there the patching forced one change
  to the tests. Each called `ServiceLoader.load(InputAdapterFactory.class)` in its own code, and a module may only
  load a service it declares `uses` for - which none of the five does, nor should: they are providers. Patched in,
  every one of those calls would have been a `ServiceConfigurationError`.

  They now go through `InputAdapterFactory.of`, which is what `Loader` uses and which works from anywhere: the
  `ServiceLoader` call inside it belongs to `ia`, and `ia`'s descriptor carries the `uses` with a comment saying so.
  The tests therefore still find their adapter the way the loader finds it, from modules that could not have looked
  for one themselves - and no production descriptor gained a `uses` to accommodate a test.

  The `csv` and `xml` fixtures moved too. Both tests load them with `getClass().getResourceAsStream("simple.csv")`,
  which resolves against the class's own package, so the resources had to follow the classes out of `.test`.

  **`ldr`, `server` and `app` were plain moves.** None discovers a service in a test - `Loader` reaches an adapter
  through `InputAdapterFactory.of`, whose lookup runs inside `ia` and against `ia`'s own `uses` - and everything
  their tests need is already in each main descriptor. `app`'s deleted test module said "no adapters here" and
  meant it: `StartupTest` gets as far as being refused for want of a configuration, which needs no format at all.

  **`xlet` keeps its test module, and now says why.** Its tests need an adapter that is not its own, and an adapter
  reaches the module graph only by being required: the jars declare `provides` in their descriptors and ship no
  `META-INF/services`, so nothing finds one that resolution has not rooted. `xlet` itself must not require an
  adapter - a front end has no business choosing formats - and a patched test cannot add a `requires`, having no
  descriptor to add it to. The `requires io.github.ralfspoeth.xldr.csv` in that file is therefore load-bearing, and
  without it most of `XldrServletTest` would post `text/csv` at a servlet that could find no adapter for it. That
  is a better reason than the one `ia` was exempted for, which did not survive being checked.

  **`ia` went the same way**, `FormatsTest` moving beside `Formats`. The argument for keeping a blackbox test
  module there - that it is the one thing proving the SPI is usable from outside - does not survive being checked:
  nine modules' main sources require `ia`, and seven test modules require it too, every one of them seeing only
  what `ia` exports, and five of them implementing the SPI rather than merely calling it. The export surface is
  exercised sixteen ways on every build by code that is not scaffolding. A test module adds nothing to that.

  **What it costs: IntelliJ cannot compile the patched tests.** Running one from the IDE fails with
  `Modul nicht gefunden: io.github.ralfspoeth.xmls` while compiling `spec` - naming the modules `spec`'s *main*
  descriptor requires, so the IDE is rebuilding the module path wrongly rather than missing a test-only entry.
  Reloading the Maven project, invalidating caches and delegating the build to Maven were all tried and none of
  them helped; `mvn` itself is green throughout. Tests are run with Maven, and that is the trade this layout was
  accepted on, not a defect waiting to be fixed by putting the test module-infos back.

- **`XldrServletIT` moved from `xlet` to `it`**, taking its `src/test/webapp` with it. `it` is the module for
  integration tests, and this was the one living somewhere else; it is also the only module binding failsafe now,
  which is one place to explain that rather than two.

  Publishing `xlet` at 0.36 is what makes the move worth more than tidiness. A published pom is read by people
  deciding whether to depend on the thing, and this one carried an embedded Jetty and a failsafe binding, neither
  of which has anything to do with the artifact. Both are gone; what is left is the servlet API, `ia`, `ldr`, and
  H2 and `csv` for the unit tests that do load for real.

  Nothing had to be widened to allow it. The test uses `XldrServlet` from an exported package and overrides the
  `protected dataSource()` seam, which a subclass reaches from any module that reads `xlet`. `xlet`'s own tests are
  now proxies all the way down, which was already their design - a proxy answers whatever the test told it to, and
  the container test exists to check the things it therefore cannot settle.

### Fixed

- **The README said the fixed-length adapter has no discriminator.** It has had one since 0.32, and the same file
  says so ninety lines earlier under *Which records are of a kind*, as does tutorial page 5. The adapter section
  still described the state before that release: one record selector, a second refused, "and this adapter has none
  yet". So the front page now sends a reader to the headline case through a section denying that the case works.

  The paragraph is rewritten from the code and each claim in it has a test: several record selectors, each with its
  own bounds so that a field omitting its left bound continues within its own layout rather than across two; a
  discriminator as a character range needing both bounds, since it has no previous field to continue from; `nth`
  refused there as on a field; no `selector` on a record selector; and every record taken where there is no
  discriminator.

  This survived the pass that was meant to reconcile the README's two accounts of a flat file, which is worth
  recording: that pass fixed the account that was being discussed and left the one further down the page. Two
  descriptions of one adapter in one file is the defect; fixing them one at a time is how it lasted five releases.

- **Two more of the same, in the module list.** It still said `xlet` is "not published: a front end to read and
  adapt, not a library to depend on", which 0.36 reversed and argued at length elsewhere in the same file; and it
  said `it` depends on `server` and the adapters "not on `app`", which stopped being true when `CheckIT` began
  driving the shipped command line, also at 0.36. Both now say what the poms say, `xlet` included.

  Three stale paragraphs in one release, each surviving a change that was documented properly somewhere else in the
  same file, is a pattern rather than three accidents. The tutorial has a test that reads it; the README has
  nothing, and its module list makes claims a build could check - which module publishes, what depends on what.

### Added

- **A fixed-length record too short to discriminate belongs to no record selector.** It already did - `Bounds.of`
  answers null past the end of the line, and both discriminators refuse a null - but nothing said so, and the
  README now claims it. A truncated line is the commonest thing wrong with a real flat file, and one that silently
  joined whichever kind was declared first would put a file's rows in the wrong table.

## 0.37

The mapping-spec format is unchanged, so `mapping-spec-0.35` remains its schema. The one breaking entry below is a
new refusal by the JSON adapter, and it is not expressible in a schema: the string it now rejects is a perfectly
good selector for the XML adapter, which shares the field.

### Breaking

- **A JSON selector may no longer begin with a slash.** It used to be stripped, and that was quiet in both
  directions. `/orders` and `orders` do address the same member, so the tolerance was harmless for every path with
  no array step in it - and wrong for every path with one. A leading slash is RFC 6901's, the syntax of JSON Schema
  `$ref`, JSON Patch and OpenAPI, and the two differ exactly where it costs most: an array step there is a bare
  number, and a bare number here is a member name. So `/orders/0/id` against `{"orders":[{"id":7}]}` parsed, read
  `0` as the name of a member and looked for it on an array, found nothing, and bound SQL NULL for every row - a
  spec that validates against the published schema, loads without a word, and fills a column with nothing.

  Accepting the slash was the worst part of it, because it confirmed the belief that produced it: the author's
  evidence that the string was being read as a JSON Pointer was that the pointer had been accepted. The refusal
  happens when the adapter is built, so it lands before a file is opened, and it names the syntax the author
  probably meant rather than only the one they missed.

  The refusal deliberately stops at the slash. A bare numeric step without one - `orders/0/id` - is still read as a
  member name, because `{"0": ...}` is a legal object and forbidding it to catch a suspected mistake would refuse a
  document with every right to exist. `xldr check --sample` covers that residue: it prints the value read for each
  field, and an unexpectedly empty one shows up there.

  Nothing in this repository, its tests or its tutorial relied on the tolerance, so the migration is to delete the
  character. That restores exactly what such a spec did before - and where it does not, because the path also
  carried a bare index, the spec was already loading nothing into that column and has only now been told.

### Documentation

- **The headerless file is the front page now.** The project description opened on "files of different formats and
  layout", which is the sentence every loader writes and tells a reader nothing about which one to pick, and
  Getting Started then demonstrated a CSV with a header row - the case that needs no toolkit at all. Both now lead
  with the file this was written for: no header, several kinds of record interleaved, and a column near the front
  saying which kind each line is. The Getting Started spec is the one from tutorial page 5, so what the front page
  shows is a spec the build validates, parses and cross-checks against its own tables.

  The usual answer to such a file is a hand-written step that splits it by record type before anything
  general-purpose is allowed near it, and that step is where the format knowledge goes to hide. Saying so is the
  point of the section; no other tool is named, because the shape of the problem is recognisable without one.

- **The tutorial's headerless pair moved to pages 4 and 5**, from 9 and 10. A reader holding such a file should
  find out whether it can be read before working through six pages on constants, variables, lookups and
  expressions. Pages 1, 2, 3, 11 and 12 keep their numbers; constants through types shift up by two.

  The no-header page had been written as a diff against the types page - the same file minus its header row, with
  that page's `balance` column and its de-DE formats - so moving it ahead would have referred to a table not yet
  created, which `TutorialTest`'s accumulating-tables rule would have caught. It is now a diff against page 2
  instead, which is the better page for it: it was teaching "no header" and locale formatting at once, and only
  the first is its subject.

## 0.36

A release about knowing sooner. `xldr check` compares a draft spec against a real file and a real table before a
feed exists; `target.properties` lets a deployment say where its rows go, and a database that will not take that
answer says so at startup; three loader paths that silently produced a wrong number now refuse; and the tutorial is
read by the build, so a page cannot drift from the release without a test naming it.

The mapping-spec format is unchanged, so `mapping-spec-0.35` remains its schema and a spec that loaded under 0.35
loads under 0.36. Two of the breaking entries below are new refusals by the reader - a repeated column, and an
`INTEGRAL` that is not one - and neither is expressible in a schema. Both refuse specs and values that could never
have loaded.

### Added

- **`xlet` is published, and its README says how to deploy it.** It was the one library module not on Maven
  Central, which was a decision rather than an oversight - the argument being that a servlet is a thing to read and
  adapt rather than to depend on. That held while it was one class and a `doPost`. It now carries a spec registry, a
  concurrency limit with its own refusal, statistics behind an MXBean and the target resolution it shares with the
  file server, and a deployment that copies all of that has forked it. A fork receives no fix made here, which is
  the argument the other way and now the stronger one.

  Publishing was the smaller half. Even with the jar there was nothing to copy: no dependency snippet, and no
  `web.xml` anywhere in the documentation, so a deployer had to derive the whole deployment from prose. The README
  now carries a complete one - the servlet and its mapping, the `resource-ref` matching the `dataSource`
  init-param, `schema` and `maxConcurrentLoads`, `load-on-startup` so that the refusals really do happen at
  deployment, and a `security-constraint` that is present rather than assumed.

  Still no `.war`, deliberately. One would have to choose a URL, and it would ship without the thing that belongs in
  front of it: anyone who can POST to this endpoint can write to the target tables. The mapping and the constraint
  are one decision, and it is the deployer's.

- **The tutorial is checked by the build.** `TutorialTest` reads `docs/tutorial/*.md` and holds every spec printed
  there to the standard of a fixture: it validates against the published schema, it is parsed by the reader that
  will parse it in anger, and it is cross-checked against the record selectors and `create table` statements of its
  own page - tables accumulating across pages and a later definition winning, which is what a reader following in
  order has.

  It also settles the one claim in the documentation that nothing could check: page 3 says it is page 2's spec
  written in XML, and both are now read and compared. That is `xldr check --same-as` applied to the place that makes
  the promise.

  This replaces a Python script, and the reason is not the language. The script validated with a second
  implementation of JSON Schema, so a clean sweep said something about *its* opinion rather than about what the
  reader accepts; the test uses the validator the rest of the build already has. More to the point, a sweep nobody
  runs checks nothing, and `docs/index.html` was four releases stale before anyone noticed. Documentation drifts
  silently because drifting is all it can do.

  One test in it asserts the extraction found anything at all - twelve pages, eight JSON specs, one XML spec, five
  tables. Every other assertion here passes vacuously if a fence is written differently or a page is renamed, and a
  green run that checked nothing is precisely the failure this class exists to prevent.

  What stays a script is `tools/check-tutorial.py`: running `xldr check` over each page needs a packaged
  distribution and a database per page, which is too much in front of every build.

- **`target.properties` - the dual of `delivery.properties`, saying where a feed's rows go.**

      schema  = staging
      catalog = warehouse

  One file says how a feed's files arrive, the other where their rows land, and both are properties of the
  deployment rather than of the mapping. That is why neither is in the spec: a spec is meant to travel from test to
  production unchanged, and the schema it writes into is exactly what differs between them.

  Both settings are optional and so is the file. Without it, table names reach the database as the spec wrote them
  and resolve through the connection's own search path - how most deployments work, and how every one worked before
  this existed. A schema qualifies the insert and every lookup select alike, since a reference table a spec names
  without qualification lives wherever that feed's tables live. Each part folds like any other identifier, so
  `staging` and `STAGING` are one schema while a quoted `"My Schema"` keeps its case - which is why the name is
  assembled from folded parts rather than folded after assembly.

  `Target` carries what the deployment said; the `Loader` renders it, because that is the only thing that builds SQL
  and the only thing holding a connection. It resolves the qualifier once per load and asks the driver first whether
  this database takes a catalog or a schema in data manipulation at all - PostgreSQL answers no to catalogs, being
  unable to qualify across databases, so `catalog = warehouse` against it is now a sentence before any record is
  read rather than a syntax error on the first one. The separator stays `.`: JDBC has no schema separator, a schema
  separator being fixed by the SQL grammar, and `getCatalogSeparator` only means anything beside `isCatalogAtStart`,
  which every driver here answers the same way. The day one does not, there is one place to change.

  An unknown setting is refused rather than ignored, for a sharper reason than in `delivery.properties`: a
  misspelled `schmea` leaves the load unqualified, and an unqualified load against a search path that finds a table
  of that name *succeeds*, into the wrong schema, silently.

  `Loader` gains a `Target` and overloads that take one; the existing signatures default to `Target.none()`, so
  every other caller is unchanged. `xldr check` gains `--schema` and `--catalog`, because a check that asks the
  database about any table of that name would pass a spec the server then loads somewhere else.

  `xlet` takes the same two words as init-params, a context-param serving every xldr servlet in an application and
  a servlet's own overriding it, as `env.` already works. So a spec moves between the two front ends without
  editing and the deployment says where its rows go in whichever way it configures anything else - a properties
  file beside the spec, or `web.xml`.

  The servlet's rule is that everything is refused at initialisation or not at all, and it keeps that here: a
  target this database will not take stops it coming up, rather than becoming a `500` on the first load reported to
  a caller who did nothing wrong. `Loader.refuseUnusableTarget` is the same question every load asks, offered
  separately so a front end can ask it once - named for what it does, since the answer wanted is the absence of an
  exception and the qualifier it produces on the way is of no use to anyone not about to build a statement.

  Only when a target is named, though. That is the one time this servlet touches the database before a request, and
  a deployment naming neither asks nothing: the servlet has never taken a connection at startup, so checking
  unconditionally would mean a database that is down at deploy time keeps the whole application from coming up -
  not a change this setting is entitled to make on behalf of deployments that never asked for it.

- **`xldr check` - a spec, a sample file and the target table, compared before a feed exists.**

      xldr check spec.json --sample orders.csv --url jdbc:... --user dbuser --password

  Not the `validate` removed in 0.30 returning. That one repeated what the adapters do, and removing it was right.
  This asks what none of them can, because it holds three artifacts at once that no part of a running server ever
  does: the adapter has the spec and the file and knows nothing of the table, the loader has the spec and the table
  but only with a transaction already open. What falls between them is a mapping naming a record selector the input
  never declared, a `column` the table has not got, and a record selector that is well formed and matches nothing
  in a real file. The first is refused on the first delivery, the second is a SQL error on the first insert, and
  the third is not refused at all - the load succeeds and inserts no rows.

  `--rows N` prints the first N parsed records with their Java types, which is the half no check can do. A date
  read under the wrong pattern is still a date and a German decimal read as a plain one is still a number, so
  nothing refuses either; but the file said `01.03.2026` and the output says `2026-03-01`, and that is either what
  the producer meant or is not.

  It reads only - a connection for `DatabaseMetaData`, the sample parsed in memory - so it is safe against the only
  database that has the table. Every argument but the spec is optional, so it degrades to whatever is available.

  Bare `xldr` still starts the server exactly as before; `check` is a subcommand beside it.

  A `lookup` is checked too - its reference table, the column it returns and the column it matches on, whether it
  sits in a field mapping or in a `var`, and recursively where a lookup's key is itself one. That was missing from
  the first version and the tutorial sweep found it: both lookup pages passed with their reference tables never
  examined. A broken lookup fails on the first record, or, in a var, before a single record has been read.

  What `--rows` shows is the field selectors - what the file gives - and not what would be inserted. A constant, a
  var, an expr or a lookup's *result* needs the load rather than a reading of the file, so it says everything about
  the input side of a spec and nothing about the mapping side. Worth knowing before reading a clean run as a
  verdict on the whole thing; the README says so now.

  Written because the loop an author or an assistant works in was not closed: the published schema validates the
  document and can see none of this. `CheckIT` runs the command against a file-based H2 and provokes every finding,
  so what the command claims is checked rather than asserted.

  It also prints the **mapping plan** - where each target column's value comes from, one line each, for every kind
  of source. Nothing is evaluated: working out what an expression comes to would be a second implementation of the
  loader's engine, one that could disagree with it, which is worse than not having one. What it gives is the wiring
  in one place, which the document itself never does - a spec spreads forty columns over a hundred lines with each
  source nested inside its own object, and a column wired to the wrong source validates, loads and is wrong in
  every row.

  And `--same-as` compares two specs:

      xldr check spec.json --same-as spec.xml

  The formats are transliterations of each other and the tutorial has a page on converting between them, so
  "did I convert it faithfully?" was a question with no answer. Both are read into a `MappingSpec`, which is records
  all the way down, so the comparison is equality - whitespace, member order and the order of record selectors do
  not matter. Where they differ it names the record selector, var or mapping and shows both, since "they differ" is
  no help against a hundred lines. A comparison spec that will not parse is itself a finding: the first version
  printed to stderr and returned, so `--same-as` pointed at a broken file exited zero, which is the one answer it
  must never give.

  This depends on `Discriminator.Matches` having gained `equals` and `hashCode` over the pattern text. It held a
  `java.util.regex.Pattern`, which compares by identity, so two separately-read specs with the same `matches`
  discriminator were never equal - a record that silently was not a value type, and one nothing had noticed because
  nothing compared specs before.

  Tutorial page 11 gains a section on it, between letting the editor read the document and watching what happens
  when a feed goes quiet, which is where it belongs in the order someone actually works. The transcript there is a
  real one - the page 8 spec against the page 8 file - rather than an invented example, and the page says outright
  that the values are the part worth reading.

- **Tests for the three things the server does after a load returns**, which were the last untested paths in
  `FileProcessor` and `LoadJob` and the ones whose mistakes are least visible.

  A load that committed and could not be archived is left in `work/` with a marker and is not counted a failure -
  the split in `runLoad` that 0.35 made, and until now unexercised, because provoking it looked as though it needed
  a filesystem that fails on demand. It needs one line: `archive()` writes under `archive/yyyy/MM/dd`, so a regular
  file where the year directory belongs makes `createDirectories` throw. The feed's own `archive/` stays a
  directory, which matters - the registry remakes the four working directories on every reconcile, and breaking one
  of those would deactivate the feed rather than the load.

  A file left in `work/` by a process that died is recovered to `hospital/` with a note, and the note differs: the
  generic one says the outcome is unknown and to check the target tables, and one with a `.loaded` marker beside it
  says the rows are in and not to redeliver. Both are asserted, since the whole point of the marker is that the two
  say opposite things.

  And `env.properties` is read as UTF-8 rather than the ISO-8859-1 `Properties.load(InputStream)` assumes. A
  deliberate departure from the convention, previously written down and not checked, and invisible when wrong:
  `Grüße` would arrive as `GrÃ¼ÃŸe` in every row of every load with nothing raising a word.

  In `ServerIT` rather than as unit tests. All three are filesystem plus database plus a running server, so there is
  no unit in them to isolate, and testing them through the server needed no change to what the module exposes -
  `FileProcessor`, `LoadJob` and `Feed` all stay package-private.

### Breaking

- **No spec member is reserved any more.** `load` was, held since 0.2 against the return of the commit policy it
  once carried. What a deployment needs to say about where a load goes is now `target.properties` beside the spec,
  so the name is not coming back, and one kept open for something that is not coming is only a trap for whoever
  picks it. It becomes an ordinary unrecognised member: ignored, like any annotation, so a spec written against an
  older release and still carrying a `load` block goes on loading exactly as it does today.

- **A mapping writes each column once.** Two field mappings onto one column build
  `insert into t(name, name) values(?, ?)`, which every database rejects - on the first record of the first file,
  with the feed deployed and a producer waiting. It is now refused when the spec is read, which is the mirror of
  the rule `RecordSelectorSpec` applies to field selector names and is refused for the same reason: a spec that
  cannot load should not be readable.

  Compared as SQL sees the names rather than as strings, so `name` and `NAME` are one column and are refused
  together, while a quoted `"name"` beside an unquoted `name` is two columns and is allowed. That rule and its
  reasoning moved out of `Loader`, where it had been private, into `SqlIdentifier` in the spec module - a record
  mapping cannot tell whether two names are one column without folding them exactly as the loader does, and a
  second copy that could drift is what this project keeps removing.

  Nothing that loads today breaks: a spec with a repeated column has never been able to load. Turned up while
  writing a `CheckIT` fixture that happened to contain one.

### Fixed

- **`bin/xldr.cmd` starts the server.** It passed `-p "%MODULES%"`, a variable nothing ever set, so the Windows
  launcher handed the JVM an empty module path and could never have worked. The three lines above it that build
  `MODULEPATH` were right; only the one that used it was wrong. The shell script was unaffected, which is why this
  survived: the distribution was only ever started from one of the two.

- **An `INTEGRAL` that is not a whole 64-bit number is refused rather than quietly changed.** Two paths ended in
  `Number.longValue()`, which drops a fraction and wraps an overflow: `1,5` loaded as `1`, and a twenty-five digit
  account number loaded as whatever its low bits said. The load reported success both times.

  In `Formats` it happened only where a `numberFormat` was configured. The canonical path never did it -
  `Long.parseLong` refuses both - so the two disagreed, and which one a field got depended on a pattern that had
  very likely been set for the money column beside it. Nothing in the spec says that setting reaches the id column
  at all.

  The JSON adapter had the same mistake on a path with no pattern in it: a JSON *number* literal is already a
  `BigDecimal` when the adapter sees it, so `{"qty": 1.5}` declared `INTEGRAL` became `1` with nothing configured
  and nothing said. That was the quieter of the two. Both now go through one shared rule, `Formats.integral`,
  rather than a second copy to keep in step.

  Only a non-zero fraction is refused: one `numberFormat` covers a whole file, so a pattern with decimal places is
  the ordinary case even where some columns are whole, and `1.00` under `#,##0.00` is exactly one and still loads.
  `FP` is untouched - it is the type that says it may be approximate.

  Found while writing a test for a claim on tutorial page 8, which is also corrected: it said `INTEGRAL` has no
  upper bound, where `DataType` has always documented it as a `Long`. The README had it right.

## 0.35

Which records of an input belong to one record selector is a type with three cases rather than two nullable fields,
which is a change to the code and not to the spec: `mapping-spec-0.35` is published, and the one thing it says that
`mapping-spec-0.32` did not is that a selector may not be blank. Everything else that reads under 0.34 reads under
0.35.

### Fixed

- **A JSON record selector carrying only a discriminator was silently ignored.** The filter the author wrote was
  dropped and the load ran over the whole document, reporting success.

  Only JSON was actually losing one, and the reason is worth recording because it is what `Locator` below was
  written for. A record selector used to carry two nullable fields, a `String selector` and a `Discriminator`. Both
  together was refused, so a discriminator could reach an adapter only where the selector was absent - and for XML
  and Excel an absent selector was itself refused, which caught it by accident. For JSON an absent selector
  legitimately means the whole document, so there was nothing left to notice.

  The two that caught it also complained about the wrong thing: a spec carrying a discriminator failed for want of
  a selector, which tells the author what they left out rather than what they wrote. All three now name what they
  found.

  The gap surfaced while migrating `swift-mt`, an out-of-tree adapter, to 0.34: it had the same hole, having been
  written against the same two fields.

- **A load that committed and then failed to file the input away was counted as a failure.** The load and the
  archive were caught in one clause, so a file whose rows were already in the database went to `hospital/` - which
  is where an operator looks for work to redeliver, and "Loading twice" in the README says what redelivering it
  does.

  The two are now caught separately, because only one of them means nothing happened. A load is one transaction: if
  it throws, nothing committed; if it returns, the rows are in and no later mishap takes them out. Such a file stays
  in `work/` with a `<name>.loaded` marker beside it, and `recoverWork` reads that marker on the next start - the
  existing note saying the outcome is unknown is right for a process that died mid-load and wrong for this file,
  where it is known.

  What made it reachable was `unique`, extracted as `FreeName` and tested for the first time. It checked whether the
  plain name was free, appended a timestamp and returned that without checking, so where both were taken it handed
  back a path that already existed - and every move this server makes is deliberately without `REPLACE_EXISTING`.
  It now looks until it finds a free name, bounded, and says plainly in its javadoc that it is best effort: the move
  mode is what actually refuses to overwrite, and this only makes the exception rare.

### Breaking

- **A record selector says which records are its own with a `Locator`.** Three cases and no others: `At`, a
  selector in the adapter's own syntax; `Where`, a discriminator; and `Every`, the input's records without further
  qualification. `RecordSelectorSpec` carries one of them instead of a nullable `selector` and a nullable
  `discriminator`.

  **The spec format does not change.** `"selector": "//order"` is an `At`, a `discriminator` object is a `Where`,
  saying neither is `Every`, and both schemas are untouched. This is the shape of the parsed result, not of the
  file, and no existing spec reads differently - with one exception, below.

  Two nullable fields are four states, of which one described no input at all. The constructor refused that fourth
  state and each of the five adapters then sorted out the remaining three by hand, through `requireSelector`,
  `refuseSelector` and `refuseDiscriminator` - methods that had to be called, in the right order, to have any
  effect, and were not, which is the JSON defect above. Three cases in a sealed type say the same thing without
  anyone having to remember: both-at-once cannot be constructed, `Every` is a case to handle rather than an absence
  to overlook, and an adapter that forgets one does not compile.

  The split across the five adapters turns out to be clean, which is the argument for the type more than any of
  this: XML, JSON and Excel accept `At`, CSV and fixed-length accept `Where`, all five accept `Every`, and each
  refuses exactly what is left. What a record selector is asking - *which records are mine?* - has two answers a
  format can offer, and every format offers one of them.

  Since both-at-once can no longer be built in Java, the rule that refuses it moved to the readers, which are now
  the only things that can encounter it. It is a rule about spec files, and it lives where spec files are read.

  Gone from `RecordSelectorSpec`: `selector()`, `discriminator()`, `requireSelector()`, `refuseSelector`,
  `refuseDiscriminator`, and the three-argument convenience constructor. In their place, `locator()` and a switch.

- **A blank record-selector `selector` is refused.** It used to mean two things: XML and Excel refused it, while
  JSON resolved it to the whole document. `Locator.At` refuses a blank selector for everyone, which leaves one way
  to say "every record" - saying nothing at all. A spec writing `"selector": ""` against a JSON input should drop
  the member.

  `mapping-spec-0.35` is published for it, in both formats, and `mapping-spec-0.32` is frozen and goes on
  describing 0.32 to 0.34. It is the only difference between the two: a spec that does not write a blank selector -
  which is every spec anyone has written on purpose - validates against either.


- **A record selector's field selectors have distinct names.** A spec that repeats one is refused when it is read,
  in either format and therefore for every adapter.

  This replaces five undocumented tie-breaks. Each adapter builds a map keyed by that name and each was quietly
  picking a winner - the CSV adapter the first declaration, the other four whichever the loop reached last - and
  none of them said so anywhere. The rule now sits on `RecordSelectorSpec`, which is the only place it is one rule
  rather than five.

  Refused rather than resolved, because nobody writes a duplicate on purpose. It is a name that was meant to be
  different, so the field the author intended is missing, and the mapping naming *that* one fails somewhere else
  entirely with a message about something else. The CSV adapter used to excuse it as "the second is never read
  from, so it is not judged", which is the same excuse this toolkit refuses for a selector matching no column.

  It matters most in a fixed-length layout, where a field may continue from the one before: a repeat there does not
  merely shadow the earlier declaration, it moves every field after it.

  Not expressed in the schemas. XSD 1.0 could say it with `xs:unique` and JSON Schema cannot, and adding it to one
  would make the two disagree about a rule the reader already enforces.


### Added

- **The JSON schema has a test.** It was the stricter of the two published files and the one nothing checked, which
  is the wrong way round. XSD 1.0 cannot say "exactly one of these", so three rules live only in the JSON schema:
  that a field mapping carries one value source, that a var's source is not a field selector, and that a record
  selector is not both pointed at and filtered. Any of them could have been wrong for as long as nobody looked.

  `JsonSchemaTest` does for it what `XsdTest` does for the XSD - validates fixtures against the file served from
  GitHub Pages, read out of the repository so that the file an author downloads is the file tested. Where a rule is
  one the reader enforces too, the test asserts both, since the point is that they agree.

  This adds `com.networknt:json-schema-validator` to the `spec` module in test scope, which brings Jackson, slf4j
  and `ethlo:itu` with it. That is a large tree next to a module whose own JSON dependency is deliberately small,
  and it is the price of the schema being checked by something that implements the specification rather than by
  assertions about the schema's own text. Nothing ships with it.

- **Tests for `Config`, `FeedRegistry` and `ServerStatus`**, the three largest untested classes in the server.

  `Config` is pure parsing and needed no change to be testable. The other two are package-private and are now
  public, for the reason `FreeName` was in 0.35: so that a test in another package can reach them. `Feed` stays
  package-private, so what those tests can ask is how many feeds are active and which inbox belongs to one - which
  is the surface a caller has anyway, and a better thing to assert against than the internals.

  What this covers is the transitions that decide whether a deployment comes up: a directory that is not a feed, a
  delivery without a spec, a spec that will not parse, two spec files, a spec appearing beside a pending feed, a
  delivery removed. And the gauges a monitor believes - in particular that a hospitalised file and the `.log`
  explaining it count as one patient and not two, which would have put every alert threshold out by a factor of two.

  Still untested: `FileProcessor`, whose failure paths need a filesystem that fails after a successful commit, and
  `LoadJob`. Both are larger pieces than these.
- **The fixed-length adapter discriminates.** It was the last flat format that could not, and the gap was written
  into three documents rather than closed. A record type in columns 1 to 2 is the classic fixed-length layout, and
  until now such a file could not be loaded at all: the adapter took one record selector and read every line as one
  kind.

  It says where to look the way everything else in that format does, with a character range - `{ "selector": "0:2",
  "equals": "OR" }`. `nth` is refused there for the reason it is refused on a field selector: a fixed-length record
  has offsets and no components to count. A range that omits its left bound is refused too, since that spelling
  means *continue from the previous field* and a discriminator has none.

  Several record selectors are allowed again as a consequence, each with **its own layout**. That is the part worth
  knowing: a field may omit its left bound and continue where the previous one ended, so a layout is a running
  total, and when the selectors shared one map the total ran across them - the second record type came out anchored
  to the first one's last field, silently. 0.34 refused a second selector to close that hole; this keeps the hole
  closed by construction and there is a test that says so, which there could not be while two were refused.

- **A written answer to what happens when a file is loaded twice**, under "Loading twice" in the README, because
  the toolkit had a position on this and had never said it. XLDR inserts and does not merge. Retrying a *failed*
  load is safe - it committed nothing - and loading a file that already succeeded is not.

  The reason it stops there is that the target is a landing zone: merging needs the natural key, the versioning
  rule, what a soft delete means and whether a late correction supersedes, and none of those belong to a mapping.
  A spec format that grew them would be a programming language with none of the tools, next to a database that
  already has one. So the division is that XLDR owns the file arriving faithfully and in one transaction, and what
  the rows mean against what is already there is downstream.

  The section also says what a landing table wants - the filename, the load timestamp, a batch number - and notes
  that a redelivery can be made to *fail* rather than duplicate, with a `limit: 1` mapping into a control table
  carrying a unique constraint on the filename. That needs no support from the format: a record selector may feed
  two tables, and `limit` applies per mapping.

- A twelfth tutorial page, on drafting a spec with a language model. A mapping spec is a structured document with a
  published schema derived from a file and a table you can both show, which is close to the ideal shape for one to
  write - and the page is last rather than first on purpose, since a generated spec is worth having only if you can
  read it. It says what to put in the prompt (a few lines of the file, the DDL, and the schema URL *with its
  version*), lists the six things that go wrong because every other tool in this space uses slightly different
  words, and ends where page 11 does: let the editor check the mechanical part, then load twenty rows and look,
  because a spec can be valid and still put the wrong column in the wrong place.

## 0.34

### Fixed

- **The published archive no longer carries the Oracle driver.** It is a convenience in a build you made yourself
  and a redistribution when we put it on a release page, and 0.33's download had it. The assembly is unchanged, so
  `mvn package` still gives you all three; the release workflow unpacks, removes `ojdbc*.jar`, and repacks both
  archives from that one tree. A `drivers/README.txt` goes in beside the remaining two saying where the third one
  lives, since an absence with no explanation is worse than either choice.

  This is the one respect in which the download differs from a local build of the same version, which is a thing
  worth disliking and the reason it is said in four places rather than none - the workflow, the note inside
  the archive, the release notes and the README.

- The 0.33 section of this file was headed `## Unreleased` when it was tagged, and the README's BOM snippet still
  said `0.32`. Neither is touched by `release:prepare`; both are now part of the release checklist rather than of
  whoever remembers.

## 0.33

### Added

- A [tutorial](docs/tutorial/README.md) for the people who write the specs, which is the audience the README serves
  worst: it is a reference, organised by feature, and a first-time author needs a path rather than a map. Eleven
  pages under `docs/tutorial`, each adding one thing to the spec built by the page before - setting up, a first
  `spec.json` and `delivery.properties`, the same spec in XML, then constants, variables, lookups, expressions,
  types and notation, a file with no header, several kinds of record, and what the three kinds of failure look
  like.

  Every page shows whole files rather than fragments, so what a reader copies is something that can go straight
  into a feed, and each page changes only what it is about rather than reprinting the input for the fifth time.
  Nothing about the code changed.

- The distribution is published as a **GitHub release**, so running the server no longer means building it. A
  workflow triggered by the `xldr-*` tag builds `app` from that tag and attaches the tarball and the zip, which
  makes publishing a download part of releasing rather than a step to remember afterwards - `release:prepare`
  pushes the tag that fires it. The archives are renamed `xldr-<version>-dist` on the way, the assembly naming its
  output after the module that produced it while the archive unpacks to `xldr-<version>/`.

  The README and the tutorial said `drivers/` was empty and told the reader to go and fetch a driver. It has never
  been empty: the assembly ships H2, PostgreSQL and Oracle deliberately, which is what makes the tutorial's first
  page a download and two commands rather than a scavenger hunt. Both are corrected.

## 0.32

The first change to the mapping-spec format since 0.23, so `mapping-spec-0.32` is published and `mapping-spec-0.23`
is frozen. A selector used to be a string whose meaning came from somewhere else; now each of the two things it was
doing has a name of its own.

### Breaking

- **A field selector says `selector` or `nth`, exactly one.** `selector` keeps its meaning - the adapter's own
  syntax, an XPath, a character range, a JSON pointer, a cell reference, the name of a column. `nth` counts from one
  and means **the n-th component of the record the record selector identified**: the n-th field of a separated line,
  the n-th column of a spreadsheet record counted from its range, the n-th element of a JSON array, the n-th child
  element. A fixed-length record has offsets and no components, so `nth` is refused there when the adapter is built.

      "selector": "1"   with header absent   ->  "nth": 1

  It used to be that `"3"` meant *the column named 3* where a CSV file had a header and *the third column* where it
  had not, decided by a property several lines away - so a file whose header really did name a column `3` could not
  be addressed at all. Two names rather than one attribute of two types, because XML cannot express the second: an
  attribute is text, so a reader would have had to guess by shape, and the two formats would have quietly stopped
  meaning the same thing. And **not** `column`, which a field mapping has always used for the database column it
  writes to.

  Where the *data* has no n-th component - a JSON record that is an object, a line with fewer fields - the value is
  `null`, only the data being able to say so. Where the *format* has none, the spec is refused.

- **A flat record selector says `discriminator`, not `selector`.** A record selector's `selector` was doing two
  unrelated jobs: for XML, JSON and Excel it *locates* records, while for a flat file every line is a candidate and
  the question is which to keep. The second now has its own element, and says both things the old form could not -
  which component, and whether by value or by pattern:

      "selector": "O"   ->  "discriminator": { "nth": 1, "equals": "O" }

  Exactly one of `nth` and `selector` for where, exactly one of `equals` and `matches` for what. A pattern matches
  the whole value and is compiled when the adapter is built. Naming the component is what makes a *headed* file with
  a type column readable - the case that, a release ago, made the `validate` heuristic indefensible. A spec still
  carrying a `selector` on a flat input is named and refused, since ignoring it would leave every line matching every
  record selector.

- **An Excel `nth` counts from the record's range, not from the sheet.** `selector: "3"` is column C wherever the
  record sits; `nth: 3` is the third column of the range, so `data!C2:F10` makes it E. They agree only for a range
  starting at column A. The digit form of `selector` is unchanged and kept for the specs that use it.

- **A `limit` is a whole number in both formats.** The XML reader parsed the attribute and threw on anything else;
  the JSON reader asked for an int and read `"limit": "100"` as *no limit* - a spec meaning to cap at a hundred rows
  that loaded the file. Both refuse it now. Nothing here changes for a spec that wrote the number unquoted.

- **A CSV record selector the input does not declare is refused**, as it already was by xlsx, xml and json. It used
  to answer with no rows, which is indistinguishable from a file that held none, so a mapping with a typo in its
  `recordSelector` was a green load of nothing on a CSV feed and a refusal on any other input. Nothing cross-checks
  a mapping against the record selectors the input declares, which makes the adapter the place the two names first
  meet.

- **The fixed-length adapter refuses four things it used to ignore**, which is the same consistency reached from
  further back - it did not look at the record selector at all.

  A name it does not declare, as above. A `selector` on the record selector, as the CSV adapter does; specs written
  before the discriminator existed carry one, and this adapter read and discarded it. A field the record selector
  does not declare, which used to reach a map lookup and come back as a `NullPointerException` from inside a stream.
  And a **second record selector**: the two used to be flattened into one layout, so a field name declared in both
  kept whichever the stream yielded last, and the rule that an omitted left bound continues from the previous field
  ran *across* the two - a layout written as a list of end positions came out anchored to a field of the other record
  selector, silently, with the load reporting rows the whole time.

  Every line of a fixed-length file has the same layout, so there is nothing for a second record selector to select.
  A file that interleaves record types needs a `discriminator`, which this adapter does not have yet; the README no
  longer implies otherwise.

### Changed

- `mapping-spec-0.32` carries `nth`, `discriminator`, and the exactly-one-of rules in the JSON schema, XSD 1.0 being
  unable to express them - so the JSON schema is now the stricter of the two by one more rule. Both schemas type
  `nth` as an integer, which is the payoff of two names: `nth="first"` is refused by an editor before any adapter
  sees it.

- The two spec readers no longer carry a copy each of the rules about what a spec may say. A package-private
  `SpecNode` asks a format five questions - how to read a named value as text, as any scalar, as a whole number, as
  a constant, and how to show itself in a complaint - and the three exactly-one-of rules are written once against
  those. The traversal stays per format, that being where the two genuinely differ and where a mistake is a failing
  test rather than a slow divergence. The `limit` above is what the duplication had already cost.

## 0.31

Nothing about the mapping-spec format changed, so `mapping-spec-0.23` remains its schema and a spec that loaded under
0.30 loads under 0.31. Nothing about the published artifacts changed either: this is the distribution's layout, and
what it says about which parts of it a deployment is meant to choose.

### Changed

- The distribution separates what has to be there from what a deployment chooses. `lib/` keeps the application and
  the toolkit - remove anything from it and nothing starts - while the input adapters move to `modules/`, beside the
  two directories that already held a choice: `xl/` for Excel and `drivers/` for the database. One directory per kind
  of choice, each resolved by service binding, so choosing is moving jars.

  `jspecify` is no longer shipped. It is `provided` for a reason: every module declares `requires static
  org.jspecify`, which is a claim made to the compiler, and nothing reads those annotations at run time. It had been
  travelling with the adapters, where it would now have implied it was a format.

## 0.30

Nothing about the mapping-spec format changed, so `mapping-spec-0.23` remains its schema and a spec that loaded under
0.29 loads under 0.30. Both front ends now say what they have loaded through the same counters, one command is gone
because everything it checked is checked earlier by something that knows more, and the distribution keeps Apache POI
in a directory a deployment can delete.

### Added

- `xlet` reports what it has loaded, over JMX, as
  `io.github.ralfspoeth.xldr:type=Loader,context="/xldr",name="xldr"`. The counters are the file server's -
  succeeded, failed, records, last load, in progress, in total and per spec - plus the two things HTTP adds:
  `RequestsRefused`, a caller sending something we will not take, and `LoadsRejected`, a `503` because no permit came
  free, which is this deployment's limit rather than the caller's mistake. Counting them apart is the point; an
  operator who could not tell them apart would go looking at the database for a client's error.

  `MaxConcurrentLoads`, `AcquireTimeoutMillis` and `MaxBytes` are exposed beside them, because a rejection count
  means nothing on its own: next to `LoadsInProgress` at the maximum it is a limit too low or a database too slow,
  and next to one that is not it is an acquire timeout too short. Judging that should not require opening `web.xml`.

  The object name carries the context path and the servlet name, both quoted, so that two deployments of the same
  WAR - or one beside the standalone server - each register rather than the second being refused. The bean is
  unregistered in `destroy()`: left behind, it holds a strong reference to a class loaded by the web application's
  loader, and every redeploy would leak that loader and everything under it.

### Changed

- The distribution puts Apache POI, and the `xlsx` adapter that needs it, in an `xl/` directory of their own. POI
  brings xmlbeans, curvesapi, several commons libraries and log4j-api, which together were most of `lib/` and made
  it hard to see what the toolkit is made of. A deployment that reads no spreadsheets now deletes `xl/` whole; the
  launcher puts it on the module path when it is there and does not mind when it is not, as with `drivers/`. Named
  for the format rather than for the library that reads it, as `drivers/` is.

  The adapter goes with the libraries rather than staying in `lib/`, which is the difference between droppable and
  merely separate: left behind with its `requires` unsatisfiable, it would stop the JVM before `main`, service
  binding resolving a provider's own dependencies and a missing one being a `FindException` rather than a quietly
  absent format.
- `Statistics` moved from `server` to `ldr`, and is public. Loading is what it counts and `ldr` is what loads, so
  both front ends can reach it without one depending on the other - the same move `Loader.load` made in 0.25, for
  the same reason. It divided cleanly: nothing file-shaped came with it, because the file server's `filesWaiting`
  and `filesInHospital` are not counters at all but are computed from the directories when asked, the directories
  being the truth. The one thing that changed is the key, which counted per feed and now counts per name - the feed
  in the file server, the spec in the servlet.

### Breaking

- **`bin/xldr validate` is gone**, and with it the `validate` subcommand, `Validate` and its tests. It was written
  when an unloadable spec surfaced late; since then the checks worth having migrated one at a time to the places
  that know. An adapter refuses a selector naming no column of the file it is reading (0.26), `SpecRegistry` refuses
  a spec the deployment cannot load and the servlet does not start (0.27), and a feed that cannot activate says why.
  Each is earlier than a command, or better informed, and none of them can be forgotten.

  What went with it is the one check nothing else makes: a CSV record selector given a discriminator although the
  file has a header, which is legal and often a mistake. It went because *often* is the problem. A headed file may
  perfectly well carry a type column:

      A,B,C
      1,one,One
      1,two,Two

  where `1` is a perfectly good discriminator. The check would only get worse as the discriminator grows - naming a
  column other than the first, or matching a pattern - since then the presence of a header says nothing at all about
  whether a discriminator belongs.

  `app` no longer requires `ia` or `spec`, and its `uses InputAdapterFactory` is gone too: that had always been
  redundant, the lookup living in `ia`, which declares its own. The adapters remain `provided` dependencies, so the
  distribution still ships them.

## 0.29

Nothing about the mapping-spec format changed, so `mapping-spec-0.23` remains its schema and a spec that loaded under
0.28 loads under 0.29. One fix, to the build rather than to anything it produces.

### Fixed

- The unit tests write what a test logged to `target/surefire-reports/*-output.txt` too. 0.28 did this for the
  integration tests and stopped there, so the next release still ended in red: the unit tests start the servlet as
  well, and it says hello through `System.Logger`, which reaches JUL, which writes to stderr, which
  `release:prepare` logs as `[ERROR]`. Both runners now redirect.

## 0.28

Nothing about the mapping-spec format changed, so `mapping-spec-0.23` remains its schema. What changed is that the
CSV adapter answers to the second of the two registered separated-value media types.

### Added

- The CSV adapter reads `text/tab-separated-values`, and that type settles three settings by itself. Its IANA
  registration is shorter than RFC 4180 and stricter: a tab separates the fields, a field *cannot contain* a tab and
  so needs no quoting mechanism at all, and the first line is the field names rather than optionally so. A spec
  naming the type therefore carries no properties:

      { "input": { "mimeType": "text/tab-separated-values", "recordSelectors": [ … ] } }

  A spec may repeat what the type already says - a tab separator for a TSV file is redundant, not wrong - but one
  that contradicts it is refused at adapter creation. The media type is a claim about what the file is, so a spec
  naming TSV and then asking for semicolons describes two different files and obeying either would be a guess. A file
  that is tab-separated *without* being TSV - quoted fields, or no header - is `text/csv` with
  `"fieldSeparator": "\t"`, which is what that type is for, and the refusal says so.

  This is where the tab default went when `text/csv` took the comma in 0.26. A format now has a name instead of a
  correction.

  `mapping-spec-0.23` does not list the new type, and does not need to: its `mimeType` list is an `anyOf` beside a
  plain string, so it is what an editor offers rather than what the schema permits. A TSV spec validates; only
  autocompletion is a release behind.

### Fixed

- `validate` applies its two CSV checks to `text/tab-separated-values` as well. It had the one media type written
  into both, so a TSV spec skipped them - including the first-column discriminator warning, which matters more for
  that type than for CSV, TSV having a header always: a discriminator there is certainly wrong rather than probably.

### Changed

- The integration tests write their output to `target/failsafe-reports/*-output.txt` instead of the console. A load
  the tests fail on purpose logs a warning, `System.Logger` reaches JUL, JUL writes to stderr, and `release:prepare`
  logs a forked build's stderr as `[ERROR]` - so a release ended in a screenful of errors from a build that passed.

## 0.27

Nothing about the mapping-spec format changed, so `mapping-spec-0.23` remains its schema and a spec that loaded under
0.26 loads under 0.27. What changed is the shape of the build: the servlet front end is part of it, and is therefore
checked against the library on every `mvn verify` rather than at whatever later moment somebody bumped its version.

### Added

- `xlet` is a module of this reactor, brought over with its history from its own repository. It is the other front
  end - one input per HTTP request, loaded through a spec the deployment carries under `/WEB-INF/specs/`, for a
  servlet container - and a peer of `app` rather than a part of `server`. Like `app` and `it` it is built but not
  published: a front end to read and adapt to a deployment, not a library to depend on.

  The reason is the release before this one. Nothing checked xlet against a change to the library until somebody
  bumped its version and found out; when the CSV separator default changed, what stood between that and a broken
  front end was a grep. Now `mvn verify` stands there.

### Fixed

- `xlet`'s integration test runs. Its POM never declared `maven-failsafe-plugin`, and the parent only manages the
  version, so `XldrServletIT` matched no surefire pattern and no failsafe execution and was silently not executed -
  a green build that had never started the container it claimed to test.
- `refusesAPathBelowTheMapping`, once that test could run, expected a `400` and got a `405`. The servlet was deployed
  at an exact mapping, so a request below it never reached the servlet at all and the container's default servlet
  answered the POST. Path info exists only under a wildcard mapping, which is both where the check applies and the
  deployment the test now builds. Nothing in the servlet changed; the test had been describing a deployment in which
  the branch it tested was unreachable.

### Changed

- Javadoc runs DocLint as `all,-missing`, from plumbum 3.0.3. The other four groups have each caught something real -
  a `{@link}` to a member the referring class could not see, a `<p/>` - while `missing` reports an absent `@param`,
  `@return` or `@throws`, and the documentation here is prose with a tag added where it says something the prose does
  not. Subtracting the one group rather than passing `none` is the point: a short list gets read, a silent one is not
  a list.
- The `DataType` constants document themselves - which Java class each is delivered as - rather than leaving it to
  the enum's own comment, and the modules carry a `name`, so the reactor's output says which is which.

## 0.26

Nothing about the mapping-spec format changed, so `mapping-spec-0.23` remains its schema. What changed is what the
CSV adapter assumes when a spec does not say: the defaults are now the ones RFC 4180 registers `text/csv` for, so a
spec that names nothing beyond the MIME type reads the format that MIME type means.

### Breaking

- **The CSV `fieldSeparator` defaults to `,` instead of a tab.** A spec that relied on the tab default has to say
  `"fieldSeparator": "\t"`. Every other setting already matched the RFC: `"` quotes a field and is doubled to escape
  itself, and no character starts a comment, since the RFC has no comments and a `#` is therefore data.

      "properties": { "fieldSeparator": "\t" }

  This was the last incidental thing about the adapter. `text/csv` is a registered media type with a specification
  behind it, and an adapter that answered to that name while reading something else made every spec carry a
  correction for it.
- **`charset` defaults to UTF-8 instead of the platform default**, in the fixed-length adapter as well as in CSV. Not
  the RFC's doing - it says only that US-ASCII is common usage - but `Charset.defaultCharset()` means the same file
  loads differently under a different `-Dfile.encoding`, which is a way for a deployment to disagree with the test
  that proved the spec. UTF-8 reads every US-ASCII file the RFC contemplates; a feed on another encoding names it, as
  before. It matters most for fixed-length, where the bounds are counted in characters: the wrong charset there does
  not merely garble a value, it moves every field after the first non-ASCII byte.
- **A field selector that names no column of the file is refused.** It used to read as null for every row. That is
  what made the separator's default dangerous to change: a tab-separated file read with commas has exactly one
  column, called the whole header line, so every selector misses and the load reports success over a table of nulls.
  The message names the selector, lists the columns the header carried and says which separator they were split on:

      selector 'id' names no column of this file. Its header carries 1 column(s):
      [id\tname], split on fieldSeparator ','

  A column merely missing from *some line* is still null - that is a short line, not a spec that does not fit its
  file - and a name the spec never declared is still null under `fieldsFromHeader`, which is a question rather than
  a claim.

### Fixed

- A hospitalised input has its `.log` beside it from the first moment it is visible. The file was moved into
  `hospital/` and the log written after, so for a short window - and permanently, if the process died inside it - an
  operator would find a failed input with nothing saying why, and `filesInHospital` would count it, that gauge
  counting everything that is not a `.log`. The log is written first now and the move is last, which costs nothing:
  the log is named after the input, and the input's name is chosen before either is written.
- `validate` understands `header = present`. It read the setting with `Boolean.parseBoolean`, which knows `true` and
  `false` and makes `false` of everything else - so a spec spelling it the way the documentation recommends was taken
  for a headerless one, and skipped the discriminator check that exists for headed files exactly. The spelling most
  likely to be written was the one spelling that got no warning. A setting that is none of the four is now reported
  rather than guessed at.

### Added

- `Header` in `ia`, beside `Formats`: one reading of the `header` setting, for everyone who has to know what it says.
  The CSV adapter is not the only one - `validate` reasons about a spec without ever creating an adapter, and cannot
  depend on an adapter module to ask, since adapters arrive by `ServiceLoader` and any of them may be absent. Two
  readings of one setting is one too many, and the bug above is what that costs.

### Changed

- The `header` default stays `present` and the `emptyLine` default stays `skip`, both now documented as xldr's
  decisions rather than the RFC's. The RFC registers `header` as a MIME parameter and says in as many words that an
  implementation choosing not to use it must decide for itself; a selector names a column, so a headerless file has
  no names to offer. And by the RFC's grammar a blank line is a record of one empty field, which no implementation
  reads it as and nobody writing a file by hand means.

## 0.25

Nothing about the mapping-spec format changed, so `mapping-spec-0.23` remains its schema and a spec that loaded under
0.24 loads under 0.25. What changed is that loading one input is now one call, in `ldr`, instead of something each
front end assembled for itself.

### Added

- `Loader.load(spec, source, ambient, connection)` loads a whole input as one transaction: it finds the adapter for
  the spec's MIME type, runs every record mapping over the input, and commits - or rolls back if any mapping failed -
  closing the connection either way. Embedding the toolkit is now two lines rather than a dozen:

      var spec = readSpec(Path.of("/var/lib/xldr/people/spec.json"));
      int rows = Loader.load(spec, () -> Files.newInputStream(file), Map.of(), connection);

  This is what the file server does with a file that has arrived, and what a web application would do with a request
  body. It was private to `server`, wrapped around a feed directory, so the second caller would have had to depend on
  the watcher and the feed registry to reach it.
- `InputSource`, in `ldr`: a source an input can be opened from, more than once. Not an `InputStream` and not a
  `Supplier<InputStream>` - a spec may carry several record mappings and each is run over the whole input, so the
  input is opened once per mapping. A file reopens; anything read from a socket has to be spooled first. The name
  says "again" so that nobody discovers it from a load that quietly imported one mapping's worth of rows.
- `InputAdapterFactory.of(inputSpec)` finds the factory for an input spec, the counterpart of
  `MappingSpecReader.of(Path)`. There were three copies of that `ServiceLoader` loop - in `LoadJob`, in
  `bin/xldr validate`, and about to be a fourth - and knowing which factory reads a spec is knowledge about
  factories.

### Changed

- `LoadJob` keeps only what makes a file a *feed's* file: the file name and the feed's `env.properties`. The loading
  itself is the shared call.
- The `uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory` clause moves to the `ia` module, since the lookup now
  runs there. A caller no longer declares it - putting the adapters on the module path is enough.

## 0.24

Nothing about the mapping-spec format changed, so `mapping-spec-0.23` remains its schema and a spec that loaded under
0.23 loads under 0.24. One bug, in how the toolkit finds its own services.

### Fixed

- The spec readers and the input adapters are found with the loader that defined the service rather than with the
  calling thread's context class loader. The one-argument `ServiceLoader.load(Class)` resolves against the thread
  context loader, which is set by servlet containers, test runners and application frameworks, each to something of
  their own - and when it is set to a loader that cannot see the xldr modules, the lookup finds nothing and says
  nothing. `MappingSpecReader.of` then returns empty, `readSpec` refuses every spec with "unsupported mapping spec
  format", and a feed never comes up for a reason having nothing to do with its files.

  It bit exactly where it is hardest to read: a downstream integration test running under failsafe, whose unit tests
  running under surefire found the same providers without trouble. Three call sites are affected -
  `MappingSpecReader.of`, `LoadJob` when it builds the adapter, and `bin/xldr validate`.

  Nothing in the API changes, and a deployment that was working goes on working. What changes is that one that was
  not now works too, and that embedding the server no longer depends on what the embedding thread's context loader
  happens to be.

## 0.23

Delivery leaves the mapping spec. Which files a feed claims, and whether a marker announces them, is a property of the
deployment rather than of the mapping - it differs between test and production while the spec does not - and it was
in a document that promised to travel between the two unchanged. It now lives in a `delivery.properties` beside the
spec, which the `server` module owns and reads.

### Breaking

- `accepts` and `sentinel` are gone from `InputSpec` and from both readers. Every feed needs a `delivery.properties`
  beside its spec, holding exactly one of them:

      accepts = glob:*.csv

  A spec still carrying either is refused by `mapping-spec-0.23` and by the readers, rather than ignored, so that it
  is moved and not merely dropped. Nothing else about the format changed, and the schemas are published as
  `mapping-spec-0.23`; `mapping-spec-0.21` stays where it is and goes on describing 0.21 and 0.22.
- The delivery file is what makes a directory a feed. A directory holding only a spec is not one, and says so at
  WARNING - it is the likeliest way for a feed not to come up, and it used to be the quietest.
- Unknown keys in `delivery.properties` are refused rather than ignored. A properties file has no schema, and a
  misspelled `acccepts` would otherwise leave a feed claiming nothing with nothing to say about why.
- `FeedStatus` carries a `state`, `ACTIVE` or `PENDING`, which changes the MXBean's composite type. `getFeeds()` now
  lists every registered feed rather than only the ones that can load - without that the totals disagreed with the
  rows, since `getFilesWaiting()` counts the inbox of every registered feed and a file waiting in a pending one would
  have been in the gauge and in no row. `getFilesInHospital()` counts registered feeds too, so a feed that lost its
  spec while a load was in flight still reports its patients. `getActiveFeeds()` is unchanged and still counts only
  the feeds that can load.

### Added

- A feed with a delivery file and no spec is real but pending: its directories exist and its producer may deliver, and
  what arrives waits in `in/` until a spec appears, at which point the backlog is loaded without being delivered
  again. The two files come from different hands and no longer have to arrive together.
- A change to `delivery.properties` reloads the feed, as a change to the spec always has. Editing which files a feed
  claims is no more structural than editing a selector, and neither needs a restart.
- `bin/xldr validate` checks the delivery file beside each spec, using the server's own reader rather than a second
  copy of the rules, and reports a missing one as the problem it is.

### Changed

- `Feed` is a sealed pair rather than one record with a nullable mapping spec, so a feed that cannot load yet cannot
  be handed to the loader at all. `Delivery` is likewise sealed over its two forms, which turns "exactly one of
  `accepts` or `sentinel`" from a check into the shape of the type.

## 0.22

Nothing about the mapping-spec format changed, so `mapping-spec-0.21` remains its schema and a spec that loaded under
0.21 loads under 0.22 unedited. What changed is what the documentation says - including two things it had been
getting wrong - and the nullness annotations behind it.

### Documentation

- The schema page at [ralfspoeth.github.io/xldr](https://ralfspoeth.github.io/xldr) now documents the adapters, not
  only the schemas: the `properties` each one reads, and the syntax of its record and field selectors, one section
  per MIME type. It is the page someone has open while writing a spec, and until now it could tell them which schema
  to point at but not what to write.
- A fixed-length field selector always needs its right bound; only the left may be omitted. The README had said the
  left one was optional without saying the right one was not.
- An Excel field selector may be a 1-based column index - `3` is column `C` - alongside the letter and R1C1 forms.
  Both R1C1 offsets have to be written even where one is zero, though the sign may be left off a positive one, and a
  relative reference that lands off the sheet is an absent value rather than an error. None of this was written down.

### Nullness

- `@Nullable` now reaches the places the audit had left: the expression bindings and the private helpers behind them
  in `Loader`, and `Row.get` as the XML adapter implements it. With that the annotations describe the code rather
  than an intention, which is the point of `@NullMarked` at all - an annotation that is wrong is worse than none,
  because tools act on it.

## 0.21

The field types are renamed, which is why this release has a schema of its own. Beyond that it is a release about
promises kept: every module is now `@NullMarked`, the annotations that were missing under it are in place, and the
guards an IDE had removed on the strength of the incomplete ones are back.

### Breaking

- The field types `STRING`, `INTEGER` and `FLOAT` are now `TEXT`, `INTEGRAL` and `FP`. Every spec naming one of them
  has to be edited: the readers uppercase what they find and hand it to `DataType.valueOf`, so an old name is an
  `IllegalArgumentException` when the spec is read, not a silent default. `DECIMAL` and `DATE` are unchanged, as is
  leaving the type out, which still means text. The new names are none of Java's on purpose - nobody should read a
  spec's `FP` as a `float` or its `INTEGRAL` as an `int` and infer a width from it; `FP` is a `Double` and rounds,
  `DECIMAL` is a `BigDecimal` and does not.
- The schemas are published as `mapping-spec-0.21`; `mapping-spec-0.13`, which describes 0.13 to 0.20, stays where it
  is, so a spec pinned to it goes on validating against the vocabulary it was written for. A spec moving to the new
  names moves its `$schema` or `xsi:noNamespaceSchemaLocation` with them.

### Fixed

- A file that arrives in `in/` after its spec has been removed is no longer loaded. The registry is authoritative but
  not instantaneous - the feed directory and its `in/` are two watch keys on two threads, and the periodic scan takes
  the active feeds before it walks their inboxes - so both paths had a window in which a deactivated feed could still
  pick something up. `FileProcessor` now stats the spec file once more immediately before the claim, which is the last
  moment at which the answer still costs nothing and the first at which the step becomes irreversible. A marker file
  is left alone as well, rather than being consumed by a feed that is off.
- A load interrupted while waiting for a slot released a permit it had never acquired, so `xldr.maxConcurrentLoads`
  grew by one every time it happened. The permit is now acquired outside the `try` whose `finally` returns it.
- `Validate` rejected exactly the specs it should have accepted: a `csv` input with `discriminator` and a header row
  is legal, and the check had kept a negation through a rewrite into `!(!isCsv || !header)`.

### Nullness

- Every module is `@NullMarked` with `requires static org.jspecify`, `json` being the last to join. The annotations
  are compile-only, so nothing reaches your runtime.
- Four null guards in `FileProcessor` had been lost to the incomplete annotations and are restored: `onArrival` and
  `scanInbox` had lost their `sentinel == null` branches, `process` and `processSignalled` their `claimed != null`
  ones. `@NullMarked` had told the IDE those expressions could not be null, so it offered to remove conditions it
  believed were always true. The `scanInbox` one broke every feed that delivers with a marker: its filter still
  guarded, so with no sentinel every file became pending and `Sentinel.dataFileOf` was then called on null, which the
  reconciliation reported as a failure.
- `@Nullable` is now on what can be null: `Feed.sentinel` and `Feed.acceptMatcher`, mutually exclusive by
  construction; `FeedRegistry.acceptMatcher` on both sides; `FileProcessor.claim` and `claimOrLog`, whose javadoc had
  said "or null if it was not ours to claim" over a bare `Path` return; `Statistics.lastLoad`, `lastFailure` and the
  `text` that exists to render them; `Watcher.watchThread`; `Validate.checkPattern`; and three private methods in
  `Loader`.

## 0.20

Nothing about the mapping-spec format changed, so `mapping-spec-0.13` remains its schema and a spec that loaded under
0.19 loads under 0.20. What changed is how the server is entered, what the modules promise one another, and where the
tests that need no server live.

### Breaking

- A `Watcher` comes from `Watcher.watch(config, connectionSource)` rather than being constructed and then started;
  the constructor and `start()` are private. There is no longer such a thing as a `Watcher` that exists without
  watching, which is the state the two-step form invited a caller to forget about. The two steps remain inside the
  factory, and have to: the constructor hands `this::onEvent` to the watch service before the fields that handler
  uses are assigned, so starting the thread from the constructor would let an event reach a half-built object - and
  starting also registers a JMX bean under a fixed name and moves any file a previous run left in a `work/` into its
  `hospital/`, neither of which belongs in a constructor. The watcher wants no name at the call site, so
  `try (var _ = Watcher.watch(config, source))` is the shape, and the javadoc says so.
- The adapters - `csv`, `flt`, `json`, `xml`, `xlsx` - `requires` `ia` rather than `requires transitive` it. None of
  them exports a package, so the promise was one no consumer could observe; the only code affected is a module that
  wrote `requires io.github.ralfspoeth.xldr.csv` and leaned on it to see `ia`, which now needs saying outright. Take
  `ldr`, which does re-export `ia`, or `ia` itself.
- `server` no longer `requires java.logging`. It logs through `System.Logger`, which is in `java.base`, and nothing
  in it names `java.util.logging` - but requiring the module *chose a backend*, because the JDK's default
  `LoggerFinder` routes to JUL when it is resolved. That is a deployment's decision, the same one that took the SLF4J
  binding out of `xlsx` in 0.19. An application embedding `server` that wants its records in JUL now says
  `requires java.logging` itself. The distribution is unaffected: `app` requires it, as a runner should.

### Changed

- `server` `requires transitive java.sql`. `ConnectionSource` is exported and its one method returns a `Connection`
  and throws `SQLException`, so a module using it had to require `java.sql` on its own account to write even a
  lambda. It no longer does.
- `ConnectionPool` is package-private. HikariCP is how `app` happens to hand out connections, not something anyone
  outside it should name; the integration tests took the hint and pass `() -> DriverManager.getConnection(url)`
  instead, which is what `ConnectionSource` being a functional interface is for. `it` consequently depends on `app`
  in no form at all - it exercises the server, with no runner in sight.
- The two tests that need no database, no threads and no server - `validate`, and where the configuration is looked
  for - moved from `it` to `app` as `ValidateTest` and `StartupTest`, and run under surefire. What makes an `IT` an
  `IT` here is cost and environment; both of these write a file and call a method.
- The distribution's main class is `io.github.ralfspoeth.xldr.app.App`, was `...app.Main`. The launchers name it in
  full and were updated with it, and the jar's `Main-Class` and `ModuleMainClass` are generated from the POM, so
  `bin/xldr` and `java -m io.github.ralfspoeth.xldr.app` are both unaffected. Only a hand-written command naming the
  class outright would need changing.

### Fixed

- A file arriving in a feed's `in/` in the moment that feed was being activated could be ignored until the next
  scan. The registry kept two maps - feeds by directory, and feeds by inbox - and filled them one statement apart, so
  a watch thread asking in between found the feed active and its inbox unknown. The second map is gone: an inbox is
  `<feed>/in`, so the feed is the entry under its parent, and one map cannot fall out of step with another that is
  not there.

## 0.19

Nothing about the mapping-spec format changed, so `mapping-spec-0.13` remains its schema and a spec that loaded under
0.18 loads under 0.19. What changed is a name in the server's API, and what the `xlsx` module puts on a consumer's
runtime.

### Breaking

- `AppConfig` is now `Config`. The `App` prefix was there to tell it apart from the many other things called
  `Config` on an application's classpath, back when it lived in `app`; in `io.github.ralfspoeth.xldr.server` the
  module name does that, and `server.Config` reads better than `server.AppConfig` for a type that configures the
  server rather than any app. A mechanical rename with no change of behaviour: the members, the factory methods
  `of(Properties)` and `load(Path)`, and the properties it reads are all as they were.

### Changed

- `xlsx` no longer requires an SLF4J binding. `slf4j-jdk14` was a compile dependency and `requires org.slf4j.jul`
  stood in the module declaration, so every consumer of the Excel adapter had a *binding* - not a facade - forced into
  its runtime, and with it a decision about where log records go that belongs to a deployment rather than to a
  library. The dependency is now test-scoped and the `requires` moved to the test module. The distribution binds
  SLF4J exactly as before, because that is a runner's choice and `app` is a runner.
- `Watcher` implements `Closeable` rather than `AutoCloseable`. Its `close()` already threw nothing but `IOException`,
  so this only says so in the type. Existing callers are unaffected - `Closeable` is an `AutoCloseable` - and
  try-with-resources behaves as it did.
- `release:perform` no longer runs the integration tests. `release:prepare` runs `clean verify` before the tag is
  cut, which is the gate; `perform` then rebuilds the very source that just passed, so running them again cost
  minutes and proved nothing. Configured through the release plugin's `goals`, which is the only perform-only setting
  it has. A plain `mvn verify` is unchanged.

## 0.18

Nothing about the mapping-spec format changed, so `mapping-spec-0.13` remains its schema and a spec that loaded under
0.17 loads under 0.18. What moved is where the server's code lives.

### Breaking

- The server is split in two. `io.github.ralfspoeth.xldr.server` now holds the watching and the loading -
  `Watcher`, `AppConfig`, `ConnectionSource`, the feed registry, the file processor, the JMX statistics - and
  `io.github.ralfspoeth.xldr.app` keeps only what a *runner* decides: the command line, the connection pool and the
  logging setup. Code that embedded the watcher imported those types from `...app` and must now import them from
  `...server`; nothing else moved and no visibility changed, because `Main` and `ConnectionPool` only ever touched
  `AppConfig`, `ConnectionSource` and `Watcher`, all of them already public.

### Added

- `server` is published, and is in the `bom`. `app` remains unpublished: it is the distribution rather than a
  library. An application embedding the server therefore no longer inherits picocli, HikariCP and the slf4j bridge,
  which were transitive burdens of `app` and are decisions an embedder makes for itself. `ConnectionSource` is a
  functional interface, so bringing your own database access is one lambda.

## 0.17

The mapping-spec format is unchanged and `mapping-spec-0.13` remains its schema: a deployment value is named in an
expression, which the schema already allows, and supplied by a file the server reads rather than by anything in the
spec. A spec that loaded under 0.16 loads under 0.17.

### Added

- A feed may hold an optional `env.properties` beside its spec. Every key in it becomes an expression name under the
  reserved `env.` prefix, so `${env.mandant}` reads what that deployment supplies and the same spec loads unchanged on
  the test box and in production. The file is read once per loaded file rather than cached with the feed, so an edit
  reaches the next load without a reload; it is read as UTF-8; and a spec naming a value the file does not supply
  fails that load rather than inserting a null. `env.` is a reserved prefix like `xldr.` rather than a fourth tier in
  the var-then-field fallback: an unprefixed name would shadow a column of the same name in every row, silently.
  Values are text, and adapter `properties` are out of reach - the adapter is built before any expression runs.

### Fixed

- A feed directory that already existed when the server started was not watched, so a spec written or changed in it
  was only noticed by the next periodic reconciliation - up to `xldr.scanInterval` seconds later, thirty by default.
  Only directories *created* while the server ran were watched, which is why the delay showed itself after a restart
  and not before it. Every directory below a root is now watched, whether it holds a spec or not, so `spec.json` and
  `spec.xml` appearing, changing or being removed takes effect at once. Both the README and the code said this was
  already the case; only the code was wrong.

## 0.16

Nothing about the toolkit's behaviour changed, and nothing about the mapping-spec format: a spec that loaded under
0.15 loads under 0.16, and `mapping-spec-0.13` remains its schema. What moved is where the build says things.

### Changed

- The Oracle, PostgreSQL and HikariCP versions are managed in `app` rather than in the reactor parent. Only the
  server uses them - the library modules touch no driver and no pool - so the parent no longer pins versions on
  behalf of a module that could just as well pin its own. Nothing a consumer imports is affected: the `bom` never
  carried these.
- The parent is `plumbum` 3.0.2, which brings JSpecify 1.0.1 and pins the jar plugin. The annotations remain
  compile-only, so this reaches a consumer's build only if it runs a null checker of its own.

## 0.15

### Added

- A CSV feed may say `"fieldsFromHeader": true`, and a field its record selector does not declare is then the column
  of that name - so a feed whose columns are already named as the mapping wants them declares no field selectors at
  all. A declared field still wins, which is how a column is renamed or given a type; an implicit one has no type and
  arrives as text. It is opt-in because `validate` reports a mapping naming an undeclared field, which is the check
  that catches `fieldSelector` written for `fieldSelectors`, and no spec says which columns a file will have. Saying
  so in the spec is what excuses that feed, and only that feed.

### Changed

- A quoted field may now stay open for 256 lines rather than a thousand before it is refused as unterminated. A
  record spanning more than a couple of hundred lines is a runaway quote in every feed seen so far, and the sooner
  the file is refused the closer the report is to the line that opened it.

## 0.14

Nothing about the mapping-spec format changed, so `mapping-spec-0.13` remains the schema. What changed is how a
deployment is laid out and started.

### Breaking

- The server no longer takes the configuration file as an argument. It reads `xldr.properties` from the directory it
  is started in, or from the one `--dir` (`-d`) names, so `bin/xldr conf/xldr.properties` becomes
  `cd /etc/xldr && bin/xldr` or `bin/xldr --dir /etc/xldr`. A deployment is a directory of its own rather than a path
  spelled out on every invocation, which is also what lets the server find the rest of its configuration beside it.

### Added

- The distribution ships its JDBC drivers in `drivers/` rather than mixed into `lib/`, and the launchers put that
  directory on the module path beside it. A driver is only another service provider, so installing one is copying
  its jar into a directory that says what it is for - and removing the ones a deployment does not target is the same
  operation in reverse. An absent or empty `drivers/` is fine.
- The launchers take `java` from `JAVA_HOME` when it is set and from `PATH` otherwise, resolve any symlink they were
  invoked through, and refuse a JVM older than the one required - saying so, rather than letting it fail with an
  `UnsupportedClassVersionError` that names a class file version and nothing else.
- A `logging.properties` beside `xldr.properties` configures logging, so a deployment tunes it by dropping a file in
  its own directory. Failing that the distribution's `conf/logging.properties` is used - the launchers now pass
  `xldr.home` so the installation can be found - and failing that the copy bundled in the jar. Setting
  `java.util.logging.config.file` still overrides all of them.

## 0.13

### Added

- A `comment` member, on every object of a JSON spec and every element of an XML one, for a note to whoever reads
  the spec next. The readers have always ignored what they do not know, so this changes nothing at load time; what
  it changes is the schemas, which now name the annotation and go on refusing every other unknown name. That
  refusal is worth keeping: further down a spec an unknown name is far more often a misspelling than a note, and
  `fieldSelector` written for `fieldSelectors` costs a record every one of its fields without a word from the reader.
- The schemas are published as `mapping-spec-0.13`; `mapping-spec-0.10`, which describes 0.10 to 0.12, stays where
  it is. A 0.12 spec is valid under 0.13 - the format only grew.

### Fixed

- `bin/xldr validate` reported a mapping reading a field of a record selector that declares no field selectors as
  "reads the field 'n1', but no record is in scope here" - the wording meant for a var, and pointing away from the
  mistake. It says the record selector declares no field selectors at all, which is what a spec spelling
  `fieldSelector` for `fieldSelectors` has done to itself.
- The CSV adapter ignored a field selector's `selector` and addressed columns by the field's `name` instead. Every
  other adapter reads the `name` as the handle a mapping uses and the `selector` as where to find the value, and a
  CSV spec that did the same - `{"name": "n1", "selector": "Name"}` - silently loaded nulls into every mapped
  column. It went unnoticed because a CSV field is usually called after its column, which makes the two alike. A
  spec whose names and selectors already agree is unaffected.

### Changed

- The README no longer says a JSON spec takes an extra member anywhere. The readers do ignore one anywhere, but the
  schemas allow only the named `comment` below the top level, and deliberately so - see above.

## 0.12

The mapping-spec format is untouched again, so `mapping-spec-0.10` remains the schema. What changed is how a spec
file is read: a reader says which files are its own, and naming a file is enough to read it.

### Breaking

- `MappingSpecReader.readFrom(Reader)` is `read(InputStream)`. A spec file is bytes until something decides the
  encoding, and the readers are the things that know: JSON is UTF-8 by definition, and an XML document declares its
  own, which a `Reader` would have taken the choice away from. The shorter name reads better on a type already
  called a reader.
- `MappingSpecReader` gained `accepts(Path)`, so a reader says for itself which files it takes rather than leaving
  the server to keep a list of extensions. Anyone implementing the interface has to answer it.

### Added

- `MappingSpecReader.of(Path)` returns the reader that takes a spec file, chosen among the readers registered as
  services. Picking a reader is knowledge about readers, so it lives with them rather than with each caller - and,
  being in the same module as the readers, it can be tested next to them.
- `MappingSpecReader.readSpec(Path)` goes the rest of the way: it picks the reader, opens the file and reads the
  spec, so naming a spec file is all it takes to load one. Where `of` answers whether a file can be read at all,
  this insists - a caller holding a spec file with nothing to fall back on wants the reason it could not be read,
  not an empty result it has to invent a message for. The format is refused by name before the file is opened, so
  an unsupported extension is an `IllegalArgumentException` and a missing file an `IOException`.

### Removed

- The hint that a spec still spelling `databaseTable` or `databaseColumn` gets, naming the 0.10 replacement. Two
  releases on, a spec from before 0.10 is refused as one missing `table` or `column`, like any other spec that does
  not name its target.

## 0.11

Nothing about the mapping-spec format changed, so a 0.10 spec is a 0.11 spec and `mapping-spec-0.10` remains its
schema. What changed is the Java API.

### Breaking

- `FieldMappingSpec`'s components are now `(String column, ValueSource source)`, the target before where its value
  comes from, which is the order the other records read in and the order the spec itself is written in. The two
  components have different types, so the compiler catches every call site.
- The convenience constructors are gone: `InputSpec(String, Collection)` and `RecordMappingSpec(String, String,
  List)`. Call the canonical constructor with the omitted arguments spelled out - `null` for the delivery rules,
  `List.of()` and `Map.of()` for the empty collections, `null` for no limit - which says at the call site what the
  constructor was hiding.
- The library modules are annotated for nullness with JSpecify: `@NullMarked` at module level, `@Nullable` where a
  value may legitimately be absent. Nothing changes at runtime - the annotations are compile-only, `requires static`
  and `provided` scope - but a build using a null checker will now see errors it did not see before, which is the
  point.

### Fixed

- The Excel range parser stopped stripping the sheet name off the selector, so every sheet-qualified range -
  `data!A2:C3`, the documented form - was refused as if its endpoints were malformed, and a range naming no sheet
  looked for a sheet named after the range itself. Both forms read again, and a range without a sheet name reads the
  first sheet, as it always did.

## 0.10

### Fixed

- `${now()}` was bound as a `java.time.Instant`, which JDBC 4.2 does not require a driver to support - an instant
  carries no calendar to write into a column. Oracle rejected it outright, before the type of the target column was
  even considered, so it failed against a text column too. It is bound as an `OffsetDateTime` at the JVM's zone now;
  a `ZonedDateTime` from anywhere else is converted the same way.

### Added

- The CSV adapter takes a `comment` character - none by default, since a value like `#12345` is common enough that
  the setting has to be asked for. A comment runs to the end of the record and only outside a quoted field, where the
  character is data; a line that is nothing but a comment is not a record, and a banner of them above the header is
  looked past.
- `emptyLine = stop` ends the data at the first empty line, for a feed that writes a trailer after a blank one. The
  default, `skip`, is what the adapter did before. A comment line never stops anything, whatever is left of it.
- `header` accepts `present` and `absent` beside `true` and `false`, the words the header itself is spoken of in.
- The CSV adapter reads quoted fields: inside one, the separator and the line break are data, and a doubled quote is
  one literal quote. A record therefore spans as many lines as a quoted field needs, which is what a spreadsheet
  export produces. A quote is structural only where a field begins, so a value like `5" pipe` still reads as it is
  written and a file that loads today keeps loading; the new `quote` property (default `"`) switches the whole thing
  off when set to nothing. A quoted field left open for more than a thousand lines is refused, naming the line that
  opened it, rather than swallowing the rest of the file into one record.
- Two expression functions: `format(value, 'pattern')` renders a date or timestamp as text, and
  `parse(text, 'pattern')` reads one from text in a notation no adapter recognises - per column, where the feed-wide
  `dateFormat` property is too broad a brush. `format` is also the way to put a timestamp into a *text* column and
  know what it will say, rather than leaving the rendering to the driver.
- An expression argument may be a name or another call, not only a literal, so `${format(now(), 'yyyy-MM-dd')}` and
  `${format(birthdate, 'yyyy')}` parse. A name inside a call is resolved as it would be on its own, and a field named
  there is requested from the adapter like any other.
- A JSON `"constant": null` is valid and loads a SQL NULL into the column. A missing member and a null one differ: the
  first leaves a field mapping with no source at all, which is still an error. XML cannot express it - a constant
  there is an attribute, and an attribute has no null.
- The schemas are published as `mapping-spec-0.10`; earlier ones stay where they are.

### Breaking

- `databaseTable` is now `table` and `databaseColumn` is now `column`, in both the JSON and the XML form and in the
  `RecordMappingSpec` and `FieldMappingSpec` accessors. The `database` prefix said nothing that the surrounding
  `mapping` did not, and a `lookup` had called them `table` and `column` all along, so the spec now uses one name for
  one thing. A spec using an old name is refused with a message naming the new one rather than reporting the new one
  as missing; that hint can go once specs from before 0.10 are out of circulation.

### Changed

- The connection pool is sized from `xldr.maxConcurrentLoads` rather than from Hikari's default of ten. A load borrows
  one connection for one file, so the two numbers said the same thing, and the pool could silently be the lower of
  them - at which point surplus loads queued in `getConnection()` rather than anywhere the configuration mentioned.
  An explicit `pool.maximumPoolSize` still wins, for a database that will not grant that many sessions.
- A lookup whose key is null returns NULL without going to the database. `= NULL` is never true, so the query could
  only have returned nothing.

## 0.9

### Fixed

- The CSV adapter read the whole file into memory before handing on a record. It now streams the lines, so the size
  of a file is no longer the size of the memory it needs.
- A CRLF file read on a platform whose line separator is `\n` left a stray return on the last column of every line;
  in header mode that column then matched no field selector and came out null for every row, silently. A record is
  now a line however the file terminates them.
- The insert statement was acquired in a try-with-resources while the loader's cache kept holding it, so a second
  record mapping onto the same table with the same columns would have found it closed.
- `xldr --version` reported a hard-coded `1.0` whatever the build was. It now reports the version from the jar
  manifest, or `(development build)` when run from a build directory.

### Fixed

- `FilesInHospital` counted the `.log` written beside a hospitalised file as a second sick file, so the number a
  monitor alerts on was twice the number of failures.

### Added

- The schemas are published as `mapping-spec-0.9.json` and `mapping-spec-0.9.xsd`; `mapping-spec-0.8` stays where it
  is, so a spec pinned to it keeps validating. A 0.8 spec is valid under 0.9 - the format only became more permissive.
- `bin/xldr validate` reports a CSV record selector that carries a discriminator although the feed has a header. No
  line's first column can equal it, so the feed loads nothing and reports success - the quietest way a spec can be
  wrong, and not something a schema can see.

### Changed

- A CSV `header` setting that is none of the four accepted words is refused. `Boolean.parseBoolean` used to read
  `header = yes` as `false` - a headerless read of a file that has a header, and a column of nulls to show for it.
- A record selector's `selector` is now optional, in both readers and in both published schemas. For a CSV with a
  header or a fixed-length file the whole file holds one kind of record and there is nothing to locate, yet a spec
  had to carry a selector anyway - and for CSV, where a selector is a first-column discriminator, giving one made the
  feed load nothing at all. An adapter that does need a selector (XML, JSON, Excel) reports a missing one by name.
- The loader sends inserts in batches of a thousand rather than one round trip per row. What a load means is
  unchanged: the whole input is still one transaction, rolled back entirely if anything fails.
- A failed load names the record it failed on - `record 7 of 'people' into PERSON: ...` - in the exception and in the
  hospital log, rather than leaving it to be counted out of the file. Where a driver will not say which statement of
  a batch failed, the range the batch covered is named instead.

### Breaking

- The CSV `rowSeparator` property is gone. A record is a line, terminated by `\n`, `\r\n` or `\r`. A spec that still
  sets it is simply setting a property nobody reads, so only a feed using a separator that was *not* a line
  terminator is affected.

## 0.8

### Added

- A JSON schema and an XSD for the mapping spec, published at
  `https://ralfspoeth.github.io/xldr/schema/`, so an editor validates and autocompletes a spec as it is written.
  Referenced by `$schema` or `xsi:noNamespaceSchemaLocation`, both of which the readers ignore.
- `bin/xldr validate <spec>...`, which checks what a schema cannot see: that an adapter for the MIME type exists and
  accepts every selector, and that each record selector, field selector and var a mapping names is declared by the
  input. No database, no server; exit code 1 if any spec is bad.
- An MIT `LICENSE` file, and an explicit `<licenses>` block rather than one inherited from the parent.

## 0.7

### Added

- The `json` input adapter, reading records with Greyson pointers (`data/orders`, `[n]`, `#regex`), for
  `application/json` and `text/json`.
- The `bom` module, so a consumer fixes the versions of all published modules with one import.

### Breaking

- Adapter settings moved into the input spec, under `properties`, and the `adapter.properties` file is gone. A feed
  is now one document.
- `InputAdapterFactory.setProperty` and `setProperties` are removed: a factory holds no state, and everything an
  adapter needs is in the spec it is created from.
- The JSON adapter has no `charset` setting - JSON exchanged between systems is UTF-8 by definition.
- The CSV adapter renamed `encoding` to `charset`, matching the other adapters. An existing spec does not fail; it
  falls back to the platform default.

## 0.6

Superseded by 0.7 within the day; the flat `input` settings it introduced were regrouped under `properties` there.

## 0.5

### Added

- The `flt` adapter for fixed-length records, addressing fields by character position, with an omitted left bound
  continuing where the previous field ended.
- Shared conversion settings for every text adapter - `dateFormat`, `numberFormat` and `locale` - applied on top of
  `DataType.parse`.

### Changed

- The CSV adapter honours the declared field type instead of reading everything as text.
- A value that is null or blank is absent for every type, so a blank numeric column is a missing value rather than a
  parse error, and `DATE` accepts a plain ISO date as well as a timestamp.

## 0.4

### Added

- The `xlsx` adapter covered by the integration tests, driven through the server with a real workbook.

## 0.3

### Added

- Expression value sources: a `${...}` template evaluated in the JVM and bound as a parameter, with
  `nextval('name'[, start[, inc]])` over in-memory per-load sequences and `now()`. Interpolates `xldr.filename`,
  declared vars and - per row - fields.
- Input variables (`vars`), evaluated once per load.

### Breaking

- A feed must declare exactly one of `accepts` or `sentinel`; one that declares both, or neither, does not activate.

## 0.2

### Changed

- The sentinel pattern is passed straight to `FileSystem.getPathMatcher`, and the data file is always the marker name
  minus its last dotted suffix.

### Breaking

- The commit policy is gone, along with the `load` element. The whole input is one transaction, committed when the
  file has been read in full or rolled back entirely. The name `load` stays reserved.

## 0.1

First release: the mapping-spec model and its JSON and XML readers, the input-adapter SPI, the JDBC loader, the
`csv`, `xml` and `xlsx` adapters, and the watching server with its feed directories.
