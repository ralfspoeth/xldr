# Changelog

Notable changes per release. Until `1.0` the API and the mapping-spec format may change in any release, including in
ways that break existing code and existing specs; those changes are listed here under **Breaking**.

The versions are the git tags `xldr-<version>`; the published artifacts carry the same version under the group
`io.github.ralfspoeth.xldr`.

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
