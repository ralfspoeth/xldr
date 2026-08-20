# Changelog

Notable changes per release. Until `1.0` the API and the mapping-spec format may change in any release, including in
ways that break existing code and existing specs; those changes are listed here under **Breaking**.

The versions are the git tags `xldr-<version>`; the published artifacts carry the same version under the group
`io.github.ralfspoeth.xldr`.

## 0.34

### Fixed

- **The published archive no longer carries the Oracle driver.** It is a convenience in a build you made yourself
  and a redistribution when we put it on a release page, and 0.33's download had it. The assembly is unchanged, so
  `mvn package` still gives you all three; the release workflow unpacks, removes `ojdbc*.jar`, and repacks both
  archives from that one tree. A `drivers/README.txt` goes in beside the remaining two saying where the third one
  lives, since an absence with no explanation is worse than either choice.

  This is the one respect in which the download differs from a local build of the same version, which is a thing
  worth disliking and the reason it is written down in three places rather than none.

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
