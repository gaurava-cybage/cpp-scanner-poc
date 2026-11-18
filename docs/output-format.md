# Output format (POC subset)

The format contract is `docs/reference/tinypay-facts.json`, which is the Java scanner's output. `docs/reference/tinypay-cpp-facts.json` shows the C++ target shape in full. The POC fills only part of it. Every top-level key is always present; sections the POC doesn't cover are written as empty arrays or objects.

## Formatting

Write with Jackson's `DefaultPrettyPrinter`, so the output looks like the reference file:
- `"key" : value`, with a space on each side of the colon;
- two-space indent;
- `[ {` … `}, {` … `} ]` for arrays of objects;
- `[ ]` for empty arrays.

Key order follows the reference file.

## Top level

| Key | POC value |
| --- | --- |
| `targetProjectName` | Solution file name without `.sln` |
| `projectVersion` | `"unspecified"` |
| `targetProjectRootPath` | Absolute path of the solution folder, with forward slashes |
| `buildTool` | `"MSBUILD"` |
| `generatedAt` | UTC ISO-8601 timestamp |
| `sourceLanguage` | `"C++"` |
| `schemaVersion` | `"0.1-poc"` |
| `languages` | `[ { "language" : "C++", "standard" : <from LanguageStandard>, "files" : <count> } ]` |
| `modules` | One per `.vcxproj` (below) |
| `deploymentTopology` | `architectureStyle`: `MONOLITH` when there is 1 Application project and no libraries, `MODULAR_MONOLITH` when there is 1 Application plus library projects, `MULTI_APPLICATION` when there are 2 or more Application projects, `LIBRARY` when there are none. `deployableApplicationModules` = Application projects; `sharedLibraryModules` = DynamicLibrary; `internalLibraryModules` = StaticLibrary; `aggregatorModules` and `unclearModules` are empty arrays |
| `aggregatedDependencies` | The union of module dependencies (see `modules[]` below) |
| `database` | Filled in T7 (see "database" below), in the same shape as the reference file |
| `integrations` | `{ "messageQueues" : [ ], "caches" : [ ], "externalSystems" : [ ], "ipc" : [ ] }`, with the Winsock entries from T7 under `ipc` |
| `deploymentDescriptors` | `[ ]` |
| `summary` | Counts (below) |
| `codeFlow` | Below |
| `diagnostics` | `[ { "file", "severity", "message" } ]`, the first error per failed file. Written as the **last** top-level key, after `codeFlow` |

## modules[]

```json
{
  "name" : "PaymentServer",
  "groupId" : "TinyPay",
  "artifactId" : "PaymentServer",
  "version" : "unspecified",
  "packaging" : "executable",
  "relativePath" : "src/PaymentServer",
  "buildFile" : "src/PaymentServer/PaymentServer.vcxproj",
  "hasSource" : true,
  "dependencies" : [ {
    "groupId" : "TinyPay",
    "artifactId" : "PaymentCore",
    "version" : "unspecified",
    "scope" : "compile",
    "linkage" : "STATIC",
    "internal" : true
  }, {
    "groupId" : "msbuild-link",
    "artifactId" : "sqlite3",
    "version" : "unspecified",
    "scope" : "compile",
    "linkage" : "IMPORT_LIB"
  }, {
    "groupId" : "windows-sdk",
    "artifactId" : "ws2_32",
    "version" : "unspecified",
    "scope" : "compile",
    "linkage" : "IMPORT_LIB"
  } ],
  "components" : [ ... ],
  "exposedApis" : [ ],
  "deploymentRole" : "DEPLOYABLE_APPLICATION"
}
```

- **`groupId`** is the solution name; **`artifactId`** is the project name. They are kept so readers of the Java format find the same keys.
- **`dependencies`** use the reference file's keys (`groupId`, `artifactId`, `version`, `scope`), plus `linkage` and `internal`:
  - **Project references** (`msbuild -getItem:ProjectReference`): `groupId` is the solution name, `artifactId` is the referenced project, `internal : true`, and `linkage` comes from that project's `ConfigurationType`.
  - **Link libraries**, from `AdditionalDependencies` in the `.vcxproj` (`<Link>` for the selected configuration) and from `#pragma comment(lib, "...")` in the code:
    - `artifactId` is the library name without `.lib`;
    - `groupId` is `windows-sdk` for known Windows SDK libraries (`kernel32`, `user32`, `gdi32`, `advapi32`, `shell32`, `ole32`, `oleaut32`, `uuid`, `ws2_32`, `winhttp`, `wininet`, `comdlg32`, `winspool`, `odbc32`, `odbccp32`, `shlwapi`, `version`, `crypt32`, `bcrypt`) and `msbuild-link` otherwise;
    - `linkage` is `IMPORT_LIB`.
  - **Version:** `vcpkg.json` gives it when present; otherwise `"unspecified"`.
  - **Scope:** always `"compile"`.
- **`aggregatedDependencies`** is the de-duplicated union of all modules' non-internal dependencies, without `internal`.

`packaging` and `deploymentRole` come from `ConfigurationType`:

| ConfigurationType | packaging | deploymentRole |
| --- | --- | --- |
| Application | `executable` | `DEPLOYABLE_APPLICATION` |
| DynamicLibrary | `shared-library` | `SHARED_LIBRARY` |
| StaticLibrary | `static-library` | `INTERNAL_LIBRARY` |

## modules[].components[]

```json
{
  "className" : "PaymentStore",
  "packageName" : "acme::payments",
  "filePath" : "src/core/PaymentStore.h",
  "sourceFiles" : [ "src/core/PaymentStore.cpp" ],
  "type" : "COMPONENT",
  "annotations" : [ "final" ],
  "baseClasses" : [ { "name" : "IStore", "access" : "public", "virtual" : false } ],
  "dependencyTypes" : [ "sqlite3" ]
}
```

- **`packageName`** holds the C++ namespace, joined with `::`. Use `""` for the global namespace.
- **`type`** in the POC is one of these:

  | Value | When |
  | --- | --- |
  | `INTERFACE` | Every method is pure virtual and there are no fields |
  | `ENTITY` | A struct with only data fields |
  | `EXPORTED_API` | The class is marked `dllexport` |
  | `COMPONENT` | Everything else |

- **`annotations`** can contain `final`, `abstract`, `struct` and `dllexport`.
- **`dependencyTypes`** are the types of member fields, with:
  - pointers, references and `const` removed;
  - `std::unique_ptr` and `std::shared_ptr` unwrapped to their inner type;
  - built-in types and `std::` types left out, except `std::thread` and `std::atomic<...>`.
- **Paths** are relative to the solution folder and use forward slashes. This applies to every path in the file: `filePath`, `sourceFiles`, `codeFlow.methods[].sourceFile`, `database` and `integrations`. (The Java file makes `codeFlow` paths relative to the Java source root; C++ has no such root, so one rule is used everywhere.)

## codeFlow

```json
"codeFlow" : {
  "schemaVersion" : 1,
  "projectName" : "<solution name>",
  "sourceKind" : "SOURCE",
  "entryPoints" : [ {
    "id" : "MAIN PaymentServer",
    "kind" : "MAIN",
    "module" : "PaymentServer",
    "className" : "",
    "methodId" : "#main(int, char**)"
  } ],
  "methods" : [ {
    "id" : "acme::payments::PaymentStore#save(const Payment&)",
    "module" : "PaymentCore",
    "className" : "acme::payments::PaymentStore",
    "componentType" : "COMPONENT",
    "methodName" : "save",
    "parameterTypes" : [ "const Payment&" ],
    "returnType" : "long long",
    "virtual" : false,
    "doc" : "Stores a payment and prints its receipt.",
    "calls" : [ {
      "kind" : "CALL",
      "targetClass" : "IReceiptPrinter",
      "targetMethod" : "print",
      "targetMethodIds" : [ "acme::payments::IReceiptPrinter#print(const Payment&)" ],
      "resolution" : "VIRTUAL",
      "seq" : 3,
      "controlPath" : [ {
        "kind" : "IF",
        "condition" : "rc == SQLITE_OK",
        "branch" : "then",
        "line" : 37
      } ],
      "async" : false,
      "line" : 39
    } ],
    "exits" : [ {
      "kind" : "RETURN",
      "controlPath" : [ {
        "kind" : "IF",
        "condition" : "rc == SQLITE_OK",
        "branch" : "else",
        "line" : 37
      } ],
      "line" : 42
    } ],
    "sourceFile" : "src/core/PaymentStore.cpp",
    "line" : 28
  } ]
}
```

- **Method ID:** `<namespace>::<Class>#<name>(<param types>)`.
  - Free functions use `<namespace>#<name>(...)`; functions in the global namespace use `#<name>(...)`.
  - Write parameter types as libclang spells them, with the space before `&` and `*` removed, joined by `", "`.
- **Entry point `kind`:**

  | Value | Functions |
  | --- | --- |
  | `MAIN` | `main`, `wmain` |
  | `WINMAIN` | `WinMain`, `wWinMain` |
  | `DLL_MAIN` | `DllMain` |
  | `DLL_EXPORT` | Exported functions |

- **Entry point `id`:** `"<KIND> <module>"`, or `"DLL_EXPORT <module>!<function>"` for exported functions.
- **Call `kind`:**

  | Value | When |
  | --- | --- |
  | `CALL` | A project function |
  | `DATA_ACCESS` | A database API call, or a call to a project function that directly performs one (see "Database calls" below) |
  | `IPC` | A Winsock anchor call |

- **`resolution`** is `EXACT` or `VIRTUAL`. Use `UNRESOLVED` when libclang finds no referenced declaration; in that case `targetMethodIds` is empty.
- **`seq`:** the call's order within the function body, in source order starting at 1. Calls nested in arguments, as in `f(g())`, number the inner call first.
- **`controlPath`:** the enclosing control statements, outermost first. Use `[ ]` when the call is not inside any of them.
  - `kind` is one of `IF`, `SWITCH`, `FOR`, `RANGE_FOR`, `WHILE`, `DO`, `TRY`, `CATCH`.
  - `condition` is the source text of the condition, the loop header, the `case` label or the `catch` type, with whitespace collapsed and cut at 120 characters.
  - `branch` is `then` or `else` for `IF`, the `case` label for `SWITCH`, `body` for loops, and `try` or `catch` for exception handling.
- **`async`:** `true` for thread starts (`CreateThread`, `_beginthreadex`, `std::thread` constructor), `PostMessage`, `PostThreadMessage`, `send` and `sendto`; otherwise `false`.
- **`doc`:** the brief doc comment of the function, or `""` if it has none.
- **`transactional`:** written as `true` (and only when true, as in the reference file) when the function's resolved SQL contains `BEGIN`, `BEGIN TRANSACTION`, `COMMIT` or `ROLLBACK`.
- **`exits`:** `return` and `throw` statements, each with its `kind` (`RETURN` or `THROW`), `controlPath` and `line`.
- **`methods`** contains every function and method defined in project files (not only reachable ones), sorted by `id`.
- **`entryPoints`** are sorted by `id`.

## Flow files (one per entry point)

These files are what an LLM receives to draw a sequence or flow diagram. Each one is small and self-contained.

- **Path:** `out/flows/<entry-point-id>.json`. Build the file name from the entry point `id` by replacing every character other than letters, digits and `-` with `-`.
- **Content:** only the methods reachable from the entry point, up to `maxDepth` call levels (default 6).
- **Left out:** calls into `std::`, logging functions (names containing `log`, `trace` or `debug`, case-insensitive) and trivial one-line getters/setters, each recorded in `omitted`.
- **Ordering:** `steps` are in visiting order (depth-first, following each method's calls by `seq`). `methods` are sorted by `id`. Two scans of the same code produce identical files apart from `generatedAt`.

```json
{
  "entryPoint" : {
    "id" : "MAIN PaymentServer",
    "kind" : "MAIN",
    "methodId" : "#main(int, char**)"
  },
  "generatedAt" : "2026-11-18T09:30:00Z",
  "maxDepth" : 6,
  "participants" : [ {
    "id" : "acme::payments::PaymentStore",
    "kind" : "CLASS",
    "module" : "PaymentCore"
  }, {
    "id" : "SQLITE:payments.db",
    "kind" : "DATABASE",
    "module" : ""
  } ],
  "steps" : [ {
    "depth" : 1,
    "from" : "#main(int, char**)",
    "to" : "acme::payments::PaymentStore#save(const Payment&)",
    "seq" : 3,
    "controlPath" : [ ],
    "async" : false,
    "line" : 31,
    "file" : "src/app/main.cpp"
  } ],
  "methods" : [ "<the same method objects as codeFlow.methods, only those reachable>" ],
  "omitted" : {
    "std" : 42,
    "logging" : 17,
    "accessors" : 5
  },
  "truncated" : false
}
```

Participants come in four kinds:

| `kind` | What it is |
| --- | --- |
| `CLASS` | Each class owning a reached method |
| `MODULE` | Free functions, grouped by their module |
| `DATABASE` | Each SQLite data source (from T7); its `id` is the data source `url` |
| `SOCKET` | Winsock (from T7) |

## database (T7)

Same keys and order as the `database` section of `docs/reference/tinypay-facts.json`. The POC fills it from SQLite; another database API is handled the same way once its header is named in T7.

```json
"database" : {
  "dataSources" : [ {
    "module" : "PaymentCore",
    "url" : "sqlite:data/payments.db",
    "username" : "",
    "sourceFile" : "src/core/PaymentStore.cpp",
    "line" : 18,
    "client" : "sqlite3"
  } ],
  "tables" : [ {
    "tableName" : "payment",
    "source" : "EMBEDDED_SQL",
    "module" : "PaymentCore",
    "sourceFile" : "src/core/PaymentStore.cpp",
    "line" : 24,
    "columns" : [ {
      "name" : "id",
      "type" : "INTEGER",
      "primaryKey" : true,
      "nullable" : false
    }, {
      "name" : "merchant_id",
      "type" : "INTEGER",
      "primaryKey" : false,
      "nullable" : true,
      "references" : "merchant.id"
    } ],
    "relationships" : [ {
      "type" : "FOREIGN_KEY",
      "column" : "merchant_id",
      "references" : "merchant.id"
    } ]
  } ],
  "migrationTool" : "SQL_SCRIPTS",
  "migrationScripts" : [ "db/schema.sql" ]
}
```

- **`dataSources`:** one entry per `sqlite3_open` / `sqlite3_open_v2` / `sqlite3_open16` call.
  - `url` is `sqlite:` followed by the file-name argument when it resolves (see "Resolving SQL and paths" below); otherwise it's `sqlite:<dynamic>`, with the argument's source text in `expression`.
  - `username` is always `""` for SQLite.
- **`tables`:** one entry per `CREATE TABLE` statement found in SQL text from the code (`source : "EMBEDDED_SQL"`, with `line`) or in `.sql` files in the solution folder (`source : "SQL"`).
  - Column `type` is the SQL type as written.
  - `primaryKey` comes from `PRIMARY KEY`; `nullable` is `false` when `NOT NULL` or `PRIMARY KEY` is present.
  - `references` comes from `REFERENCES t(c)`, either inline or as a table-level `FOREIGN KEY`.
  - If the same table is defined both in code and in a `.sql` file, both entries are kept, as in the reference file.
- **`migrationTool`:** `"SQL_SCRIPTS"` if any `.sql` files exist, otherwise `"NONE"`.
- **`migrationScripts`:** the `.sql` file paths, sorted.

## Database calls (T7)

Database access is recorded on the calls themselves, as in the reference file; there is no separate list.

- **A call to the database API** (declaration in `sqlite3.h`) gets `"kind" : "DATA_ACCESS"` plus these keys where known:

  | Key | Content |
  | --- | --- |
  | `sql` | The SQL text, or `"<dynamic>"` |
  | `operation` | `READ` for `SELECT`; `SAVE` for `INSERT`, `REPLACE` and `UPDATE`; `DELETE` for `DELETE`; `SCHEMA` for `CREATE`, `ALTER` and `DROP` |
  | `tables` | Table names found after `FROM`, `JOIN`, `INTO`, `UPDATE`, `DELETE FROM` and `CREATE TABLE`, de-duplicated, in order |
  | `expression` | The argument's source text, when `sql` is `"<dynamic>"` |

- **`targetClass`** for database API calls is the API name, `"sqlite3"`, just as the Java file uses `JdbcTemplate`. Winsock calls use `"winsock"`.
- **`tables`** (an array) is used instead of the Java file's `entity`, because C++ code usually has no entity classes. When `tables` has exactly one name, `table` is also written with that name, for readers that expect a single value.
- **Which argument holds the SQL:** argument 1 for `sqlite3_exec`, `sqlite3_prepare`, `sqlite3_prepare_v2`, `sqlite3_prepare_v3` and `sqlite3_prepare16*`.
- **Other SQLite calls** (`sqlite3_step`, `sqlite3_bind_*`, `sqlite3_column_*`, `sqlite3_finalize`, `sqlite3_close`) are kept as `DATA_ACCESS` calls without `sql`. They must not flood the flow files: in a flow file, consecutive SQLite calls inside one function are folded into the `prepare` / `exec` step that precedes them.
- **Calls to a project function** whose own body contains a SQLite call with a known `operation` also get `"kind" : "DATA_ACCESS"`, with the same `operation` and `tables` (one level only, as in the reference file's repository calls).

Example call:

```json
{
  "kind" : "DATA_ACCESS",
  "targetClass" : "sqlite3",
  "targetMethod" : "sqlite3_prepare_v2",
  "targetMethodIds" : [ ],
  "resolution" : "EXACT",
  "sql" : "INSERT INTO payment (merchant_id, amount, currency) VALUES (?, ?, ?)",
  "operation" : "SAVE",
  "tables" : [ "payment" ],
  "seq" : 2,
  "controlPath" : [ ],
  "async" : false,
  "line" : 61
}
```

## Resolving SQL and paths

Try these in order. Stop at the first one that gives a string.

1. A string literal as the argument. Adjacent literals (`"SELECT * " "FROM t"`) are joined.
2. A variable or parameter of the same function, initialised from a string literal (`const char* sql = "...";`, `std::string sql = "...";`).
3. A `const` or `constexpr` global or `static` member initialised from a string literal, or a `#define` that expands to a string literal (read the macro's tokens).
4. Otherwise `"<dynamic>"`, with the argument's source text in `expression`.

Simple SQL parsing is enough for the POC; don't add an SQL parser library. Use the keywords listed above, case-insensitive, and ignore SQL comments.

## integrations (T7)

Example Winsock entry, in `integrations.ipc`:

```json
{
  "type" : "TCP_SOCKET",
  "role" : "CLIENT",
  "operation" : "connect",
  "module" : "PaymentServer",
  "methodId" : "acme::net::Client#open(const std::string&, int)",
  "sourceFile" : "src/net/Client.cpp",
  "line" : 22,
  "confidence" : "CONFIRMED"
}
```

`role` is one of:

| Value | Calls |
| --- | --- |
| `SERVER` | `bind`, `listen`, `accept` |
| `CLIENT` | `connect` |
| `IO` | `send`, `recv`, `sendto`, `recvfrom` |
| `SETUP` | `WSAStartup`, `socket`, `closesocket` |

## summary

```json
"summary" : {
  "moduleCount" : 0,
  "deployableApplicationCount" : 0,
  "componentCount" : 0,
  "dependencyCount" : 0,
  "tableCount" : 0,
  "dataSourceCount" : 0,
  "messageQueueCount" : 0,
  "cacheCount" : 0,
  "externalSystemCount" : 0,
  "deploymentDescriptorCount" : 0,
  "entryPointCount" : 0,
  "methodCount" : 0,
  "callCount" : 0,
  "filesParsed" : 0,
  "filesWithErrors" : 0,
  "scanSeconds" : 0
}
```
