# Changelog

Notable changes per release. Until `1.0` the API and the mapping-spec format may change in any release, including in
ways that break existing code and existing specs; those changes are listed here under **Breaking**.

The versions are the git tags `xldr-<version>`; the published artifacts carry the same version under the group
`io.github.ralfspoeth.xldr`.

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
