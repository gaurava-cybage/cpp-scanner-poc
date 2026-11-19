# POC Findings

**Date:** 2026-11-18 to 2026-11-19  
**Task:** T0–T8 (POC complete; open items: developer review, the diagram, the real target run)

## Success criteria

| Question | Result | Evidence |
| --- | --- | --- |
| libclang via JavaCPP runs on this machine | **PASS** | All 20 PayHub files parse with no crash (`llvm-platform 22.1.8-1.5.14`) |
| Compiler settings are correct (≥ 90% clean) | **PASS on PayHub** (95%, 19/20). Real target: **pending** | Needs the third-party headers: 80% without `sqlite3.h` (T3). The one failure is the missing `librdkafka/rdkafka.h` |
| Extraction is correct (≥ 18/20 calls, 10/10 classes) | **Pending review** | `out/review-sample.md`. My own pre-check against the source: 20/20 calls, 10/10 classes |
| Output format matches | **PASS** | `OutputTest`: same top-level structure, key names and formatting as `reference/tinypay-facts.json` |
| Pattern detection is feasible | **PASS** | 74 SQLite calls and 16 Winsock IPC entries; file, line and role checked against the source and asserted in `PayHubT7Test`. Limit: only `winsock2.h` is an anchor, so `getaddrinfo`/`freeaddrinfo` (`ws2tcpip.h`) are not recorded (question 9) |
| Database facts are extractable | **Pending review** of `out/sql-review.md` | 3 data sources, 5 tables, 14 statements. **Resolved share: 11/14 = 79%** (3 `<dynamic>`) |
| Speed | **PASS** (recorded) | 39 s total for 20 files; see T8 |
| Diagram-ready | **Pending** | `out/flows/MAIN-App.json` is ready; the team's LLM diagram tool was not available to the scanner session. Known limit: the flow stops at the server's thread start (question 5) |
| libclang gaps | **PASS** (listed in T8) | None of them needs LibTooling for the POC's facts |

## Environment & Setup

### llvm-platform Version
`org.bytedeco:llvm-platform:22.1.8-1.5.14` (LLVM 22.1.8), pinned in `pom.xml`.

`.mvn/maven.config` sets `-Djavacpp.platform=windows-x86_64` so only the Windows x64 natives are pulled (Windows-only POC). The fat jar is still ~590 MB because the Windows LLVM natives alone are that large.

## Task T0: Environment Check

**Script location:** `scripts/check-env.ps1`

**Environment variables set:**
- `JAVA_HOME` = `C:\java\jdk-17.0.21.0.2+1` (User scope)
- PATH includes:
  - `C:\java\jdk-17.0.21.0.2+1\bin`
  - `C:\maven\apache-maven-3.9.12\bin`
  - `C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\MSBuild\Current\Bin`

**T0 Verification - ALL PASSED ✅**
- [x] Java: openjdk 17.0.21.0.2 LTS (requirement: 17+)
- [x] Maven: 3.9.12
- [x] MSBuild: 18.10.1 (requirement: 17.8+)
- [x] Script runs without errors

---

## Task T1: Maven skeleton and first libclang parse

**Status: COMPLETE ✅**

1. **pom.xml:** llvm-platform, jackson-databind 2.16.1, JUnit 5.10.1. Fat jar `target/cpp-scanner-poc.jar`.
2. **PayHub fixture (`D:\SampleCppCode\PayHub`) checked against the T1 list:**
   - ✅ Several projects; App references Core, Storage, Network, Messaging, Integration
   - ✅ Namespaces: `payhub::core`, `payhub::storage`, ...
   - ✅ Class hierarchy with virtual methods: `IPaymentRepository` ← `SqlitePaymentRepository`
   - ✅ `main()` in `src/App/main.cpp`
   - ✅ SQLite: `sqlite3_open` with a literal path in `SqlitePaymentRepository.cpp`
   - ✅ `CREATE TABLE` with PK + FK, `.sql` files in `db/migrations/`
   - ✅ Winsock calls and `#pragma comment(lib, "ws2_32.lib")` in `WinsockSession.cpp`
   - ✅ Call inside an `if` (`PaymentRouter.cpp`) and inside a loop (`MigrationRunner.cpp`)
3. **ClangParser.java:** parses one file with given args and returns diagnostics.

---

## Task T2: Compiler settings from MSBuild

**Status: DONE** (compiler settings and project dependency lists)

**How it works (`msbuild/MSBuildRunner.java`):**
- MSBuild is found on `PATH` (Developer prompt) or else via `vswhere.exe`. No install paths are hard-coded.
- Per project: `msbuild <proj> -getItem:ClCompile -p:Configuration=<c> -p:Platform=<p> -p:SolutionDir=<sln dir>/ -nologo`, parsed with Jackson.
- `SolutionDir` is passed because MSBuild leaves it empty when a `.vcxproj` is built on its own, but many projects use it in include paths.
- Source path = the item's `FullPath`.
- Args per file:
  - always `--driver-mode=cl -fms-compatibility -fms-extensions`;
  - `/D` from `PreprocessorDefinitions`;
  - `/I` from `AdditionalIncludeDirectories`, resolved against the project folder;
  - `/std:` from `LanguageStandard` (`LanguageStandard_C` for `.c`);
  - `/EHsc|/EHa|/EHs` from `ExceptionHandling`;
  - `/MD|/MDd|/MT|/MTd` from `RuntimeLibrary`;
  - `/FI<pch.h>` when `PrecompiledHeader=Use`;
  - `/FI` for `ForcedIncludeFiles`;
  - `-m32` for Win32.
- Unit test: `MSBuildRunnerTest` (saved `-getItem` JSON → expected args).

**Bugs found and fixed in the first version:**
1. Regex "JSON parsing" read metadata from a 1500-char window after `Identity`, so defines and `/std` were silently dropped. Replaced with Jackson.
2. PCH was never forced: MSBuild reports it as `PrecompiledHeader=Use` + `PrecompiledHeaderFile=pch.h`, not as `ForcedIncludeFiles`.
3. The file handed to libclang was the solution-relative lookup key, so libclang could not find any file (every file "Failed to parse translation unit").
4. `ExceptionHandling` was not mapped, so clang-cl ran with C++ exceptions off (`cannot use 'try' with exceptions disabled`).

**Surprise — unset environment variables in include paths:** PayHub uses `$(SQLITE_DIR)\include` (Storage, App) and `$(LIBRDKAFKA_DIR)\include` (Messaging, App). Neither variable is set on this machine and neither header exists anywhere under the solution folder. MSBuild expands them to `\include`. The scanner skips include dirs that don't exist and prints them ("include dir not found, skipped").

**Project dependencies (`msbuild/DependencyCollector.java`):**
- The same msbuild call also asks for `-getItem:ProjectReference -getProperty:ConfigurationType`, so there is still one msbuild run per project.
- Project references are matched to `.sln` projects by full path; `linkage` comes from the referenced project's `ConfigurationType` (StaticLibrary→`STATIC`, DynamicLibrary→`SHARED`, Application→`EXECUTABLE`, else `UNKNOWN`; only `STATIC` appears in `output-format.md`, the others are my choice).
- `<Link>` settings are item *definitions*, which `-getItem` cannot return, so `AdditionalDependencies` is read from the `.vcxproj` XML (JDK DOM parser, no new library). Groups with no `Condition` or a `=='<Config>|<Platform>'` / `=='<Config>'` condition are used; `%(...)` and `$(...)` entries are dropped.
  - **Limitation:** libraries set only in imported `.props` files are missed (PayHub's `PayHub.Common.props` sets none). MSBuild's default libraries (`kernel32`, …) come in through `%(AdditionalDependencies)` and are therefore not listed.
- `#pragma comment(lib, "...")`: text match over the project's ClCompile files and the `.h`/`.hpp` files under the project folder. **Limitation:** a pragma inside `#if 0` or a block comment is still counted.
- `vcpkg.json`: project folder, then solution folder; `overrides[].version` beats `dependencies[]."version>="`. A version is applied only when the vcpkg package name equals the library name (case-insensitive). PayHub has no `vcpkg.json`, so this path is covered by a unit test only.
- **Ordering (not specified in `output-format.md`):** project references first, then libraries; alphabetical (case-insensitive) within each group. Libraries are de-duplicated case-insensitively per project.
- Unit test: `DependencyCollectorTest` (configuration-specific `<Link>`, pragma in source and header, vcpkg versions, ordering and de-duplication).

**PayHub result (Release|x64):** Core none; Storage, Messaging, Integration → Core; Network → Core + `ws2_32` (from `#pragma`); App → Core, Integration, Messaging, Network, Storage + `librdkafka`, `sqlite3` (msbuild-link) + `ws2_32` (windows-sdk), all from `<Link>`. Matches the README note that `ws2_32` is linked both by pragma (Network) and in `App.vcxproj`.

**Surprise — build output wiped by the IDE:** twice the fat jar came out without `poc/cppscan` classes. Cause: the VS Code Java extension (`redhat.java`) compiles into the same `target/classes`. Killing every `java.exe` (to release a locked jar) also killed its language server; on restart it does a clean build and empties `target/classes` while Maven is packaging. Fix: only stop the scanner's own java processes, never all of them.

---

## Task T3: Parse the whole solution

**Status: run; 80% clean, below the 90% target — cause below**

**PayHub, Release|x64:**

| Metric | Value |
|---|---|
| Files total | 20 |
| Files clean | 16 |
| Files with errors | 4 |
| Clean rate | 80.0% |
| Total time | 31–43 s (varies between runs; once the first file, `src/core/pch.cpp`, took 10 s) |
| Slowest | `src/app/main.cpp` 3.0 s; the 3 Storage `.cpp` files ~2.4–3.0 s |

**All 4 failures are missing third-party headers, not scanner errors:**
- `'sqlite3.h' file not found`: `src/storage/migrationrunner.cpp`, `sqlitepaymentrepository.cpp`, `sqliterailconfigrepository.cpp`
- `'librdkafka/rdkafka.h' file not found`: `src/messaging/kafkaeventpublisher.cpp`

**Impact on later tasks:** T7's SQLite anchor rule needs the declarations from `sqlite3.h`. Without that header, every `sqlite3_*` call is unresolved, so the database facts cannot be produced. The header must be made available (set `SQLITE_DIR` to a folder with `include\sqlite3.h`) before T7.

**With a stub `sqlite3.h` (2026-11-18): 19/20 clean (95%), T3 target met.**
- No SQLite SDK exists on the scan machine, so `src/test/resources/stubs/sqlite/include/sqlite3.h` declares the public SQLite C API: the real signatures and constant values, no implementation, and clearly marked as a stub.
- It lives in the scanner repo, never in the target solution, and is used by setting `SQLITE_DIR=d:\cpp-scanner-poc\src\test\resources\stubs\sqlite` before the scan. PayHub's own `$(SQLITE_DIR)\include` then finds it, so no scanner code or `src/main` value is involved.
- The anchor rule matches on the declaring file name ending in `sqlite3.h`, so the stub behaves like the real header for the scanner. Replace it with the real header when one is available.
- Only `KafkaEventPublisher.cpp` still fails (`librdkafka/rdkafka.h`); no task depends on it.

**System headers:** MSVC STL and Windows SDK headers were found automatically by libclang's clang-cl toolchain detection; no INCLUDE env var was needed on this machine.

---

## Task T4: Classes and Functions

**Status: DONE**

(An earlier draft of this file reported T3 at 100% and listed T4 classes that the scanner never produced; those were removed.)

**PayHub, Release|x64:**

| Project | Classes | Functions (definitions) |
|---|---|---|
| Core | 12 | 40 |
| Storage | 4 | 25 |
| Network | 4 | 14 |
| Messaging | 1 | 3 |
| Integration | 1 | 3 |
| App | 1 | 3 |
| **Total** | **23** | **88** |

**How it works (`extract/Extractor.java`):** the T3 parse hands each translation unit to the extractor before it is disposed (one parse per file, not two). Classes and functions are kept only when they are definitions, are not in a system header **and are in a file under the solution folder**; de-duplicated by USR; owner = semantic parent. Parameter types come from the canonical (first) declaration so that T5 can build the same method ID from any translation unit.

**Assumptions and choices:**
- **Partial results, never stop:** files with errors (missing third-party headers) are still walked; libclang recovers and keeps most of the AST. Only a libclang failure to produce a translation unit drops a file.
- **Third-party headers outside the solution folder are skipped** even if they are not "system" headers for libclang (e.g. an `/I` to a vcpkg folder). Otherwise their classes would become components. A vendored header inside the solution folder is still scanned.
- **Nested classes:** `className` = `Outer::Inner`, `packageName` = namespace only. PayHub has no nested classes (covered by `ExtractorTest`).
- **Anonymous namespace:** shown as `(anonymous)` in `packageName` and method IDs.
- **Class-template constructors:** libclang spells them `LruCache<K, V>`; the `<K, V>` is dropped so the name is `LruCache`.
- `type` rules: `INTERFACE` = at least one method, all methods pure virtual (constructors/destructors ignored), no fields. `ENTITY` = struct with fields and no user-declared methods, constructors or destructors.
- `dependencyTypes` are written as spelled in the source (e.g. `core::PaymentHubService`), matching `reference/tinypay-cpp-facts.json`, and sorted.
- `"= default"` special members are not definitions for libclang, so they don't appear as functions.

**Surprises / open points:**
- **Method ID collision:** `payhub::storage::(anonymous)#ParseRail(const std::string&)` exists in two files (`SqlitePaymentRepository.cpp:20` and `SqliteRailConfigRepository.cpp:12`). They have different USRs but the ID format gives them the same `id`. **Open question:** how should IDs of functions in anonymous namespaces be made unique (e.g. add the file)? Not specified in `output-format.md`.
- `main` is spelled `#main(int, char*[])` because the source declares `char* argv[]`; the format's example shows `char**`. I follow "as libclang spells them".
- `clang_CXXRecord_isAbstract` returns false for class templates, so the template interface `ICache<K, V>` has no `abstract` annotation (its `type` is still `INTERFACE`).
- No field with an unresolved type: PayHub forward-declares `struct sqlite3;` and `struct rd_kafka_s;` in its headers, so the fields still resolve despite the missing third-party headers.
- The reference files live in `reference/`, not `docs/reference/` as CLAUDE.md and the plan say.

**Manual check (3 classes):**
1. *Base class + virtual methods:* `payhub::storage::SqlitePaymentRepository` (Storage), `src/Storage/SqlitePaymentRepository.h:15`, base `public core::IPaymentRepository`, virtual `Save`, `UpdateStatus`, `FindById`, `~SqlitePaymentRepository`; fields `db_ : sqlite3 *`, `mutex_ : std::mutex`; `dependencyTypes [sqlite3]`.
2. *In a namespace:* `payhub::core::PaymentRouter` (Core), `src/Core/PaymentRouter.h:14`, no bases, field `railConfig_ : const IRailConfigRepository &`, methods `SelectRail`, `IsDomestic`, `IsEligible` (all const); `dependencyTypes [IRailConfigRepository]`. *Nested class:* none exists in PayHub.
3. *Other project:* `payhub::network::TcpHubServer` (Network), `src/Network/TcpHubServer.h:15`, no bases, fields `service_ : core::PaymentHubService &`, `port_ : std::uint16_t`, `running_ : std::atomic<bool>`; `dependencyTypes [core::PaymentHubService, std::atomic<bool>]`.

Unit test: `ExtractorTest` parses an inline header + source with libclang (namespace, nested class, final, virtual base, interface, entity, unique_ptr/thread/std::string fields, method IDs incl. `#main(int, char**)`).

---

## Task T5: Calls and entry points

**Status: DONE**

**PayHub, Release|x64:**

| Metric | Value |
|---|---|
| Calls | 248 (EXACT 231, VIRTUAL 17, UNRESOLVED 0) |
| Calls into declarations outside the solution | 20 (Winsock anchors + async system calls) |
| Async calls | 3: `send` in `HttpRailConnector.cpp:70` and `TcpHubServer.cpp:62`, `std::thread` in `TcpHubServer.cpp:42` |
| Calls inside control flow | 77 (steps: IF 51, TRY 20, WHILE 7, RANGE_FOR 2, CATCH 2) |
| Qualified calls to virtual methods (`Base::f()`) | 0 |
| Exits (return/throw) | 133 |
| Functions with a doc comment | 0 (PayHub has no `///` or `/** */` comments) |
| Entry points | 1: `MAIN App` → `#main(int, char*[])` |

`main`'s 17 calls were checked line by line against `src/App/main.cpp` (all inside `try`, two inside `catch`). A call inside an `if`: `PaymentRouter::SelectRail` (`IsEligible` under `IF(!IsDomestic(payment))`). Calls inside a loop: `MigrationRunner::Run` (`ParseVersion` and `Apply` under `RANGE_FOR(const auto& script : FindScripts())`).

**Bug found and fixed — fatal errors stop #includes:** a missing third-party header (`sqlite3.h`) is a *fatal* error. By default clang then stops reading every later `#include`, so code that has nothing to do with SQLite lost its declarations. Example: the whole range-for in `MigrationRunner::Run` and its calls disappeared. Fix: parse with `CXTranslationUnit_KeepGoing`. Same clean rate (80%), but the 4 files now only lose the SQLite names themselves. Calls went from 243 to 247 and RANGE_FOR from 0 to 2.

**Bug found and fixed — template constructors:** the `std::thread` constructor is a template; after mapping the call to its template, the cursor kind became `FunctionTemplate`, so the `std::thread` async rule never matched. The kind is now taken before the template swap (unit test added).

**Choices (not fixed by `output-format.md`):**
- **System calls kept:** besides the anchor calls (declared in `sqlite3.h` / `winsock2.h`), calls to the **async** system functions (`CreateThread`, `_beginthreadex`, `PostMessageA/W`, `PostThreadMessageA/W`, `std::thread`) are kept too. If they were dropped, `async` could never be true for them. All other system-header calls are left out.
- **Third-party calls** (declaration outside the solution, not a system header) are kept with `targetMethodIds : [ ]` and resolution `EXACT`, like the drogon/redis calls in `reference/tinypay-cpp-facts.json`.
- **`targetClass`** is the owner's qualified name with the caller's namespace removed (`FeeCache` from inside `tinypay::core`), as in the reference file.
- **VIRTUAL `targetMethodIds`:** the declared method first, then every project method that overrides it (directly or through a chain), sorted. Each implementation's ID uses its own spelling, e.g. `SqlitePaymentRepository#Save(const core::Payment&)` next to `IPaymentRepository#Save(const Payment&)`.
- **Calls in an `if`/`while`/`for` condition or loop header** are evaluated before branching, so their controlPath does **not** include that statement. The `do` condition is treated the same way.
- **`case A: case B:`** gives one branch `"A, B"`.
- **`TRY` step:** `condition` is `""`; the `CATCH` step's `condition` is the caught type (`const std::exception& ex`, `...`).
- **Lambdas:** calls inside a lambda are kept in the enclosing function (with the enclosing control path); `return`/`throw` inside a lambda are not exits of the enclosing function.
- **Implicit constructors** (compiler-generated copy/move/default) are not recorded as calls.
- **DLL_EXPORT entry points:** members of a `dllexport` class inherit the attribute in libclang (all `Logger` methods had it). Only functions marked themselves count as entry points.

**Limitations:**
- **SQLite calls are missing** while `sqlite3.h` is absent: an undeclared `sqlite3_open(...)` is not a `CallExpr` in clang's AST, so it isn't even counted as UNRESOLVED. That's why UNRESOLVED is 0. T7 needs the header.
- **Templates:** overrides inside uninstantiated class templates are not reported by libclang, so `ICache<K, V>::Get` lists no implementation (`LruCache<K, V>::Get` exists).
- Conditions are cut at 120 characters as specified, which can end mid-expression (`... listen(listener, SOMAXCONN) ==`).

Unit tests (`BodyWalkerTest`, inline snippets): `f(g())` seq order, a `case` with two calls plus `case 2: case 3:` and `default`, `else if` path, `try`/`catch` (typed and `...`), `for`/`while`/`do` with conditions, throw inside `if`, `return` inside a lambda not counted as exit, VIRTUAL vs qualified call, `std::thread` async.

---

## Task T6: JSON in the tinypay format

**Status: DONE**

- `out/sample-facts.json` (5,500+ lines) and `out/flows/MAIN-App.json` (37 steps, 27 methods, 24 logging calls omitted, not truncated) are written by the CLAUDE.md command `--solution D:\SampleCppCode\PayHub\PayHub.sln --out out\sample-facts.json`.
- `--flows` defaults to a `flows` folder next to `--out`; stale `*.json` files in that folder are deleted first (it is the scanner's own output folder).
- **Second run:** identical files except `generatedAt` **and `summary.scanSeconds`** (see conflict below).
- Top-level keys = the reference file's keys + `sourceLanguage`, `schemaVersion`, `languages` (after `generatedAt`, as in `reference/tinypay-cpp-facts.json`) + `diagnostics` (last). Checked by `OutputTest`, which also checks the formatting style against `reference/tinypay-facts.json`.
- Formatting: Jackson `DefaultPrettyPrinter`, which writes CRLF on Windows. The reference file is also CRLF with no trailing newline, and ours matches.
- `@JsonPropertyOrder` on every record, so key order never depends on reflection.

**PayHub results:** 6 modules, `MODULAR_MONOLITH` (App + 5 static libraries), 23 components, 88 methods, 239 calls, 1 entry point; `aggregatedDependencies` = librdkafka, sqlite3, ws2_32; `diagnostics` = the 4 missing-header files; `languages` = c++17 (9 files), c++20 (11 files).

**Bug found and fixed:** calling a local lambda (`l(...)`) was recorded as a call to `(anonymous)::(lambda at D:\...\main.cpp:51:21)#operator()`. That put an absolute path in the output and pointed at a method that isn't in `methods`. Calls *to* lambdas are now skipped; the lambda's own calls were already part of the enclosing function (T5 rule). Calls went from 248 to 239.

**Choices (not fixed by `output-format.md`):**
- `languages` has **one entry per language standard** (PayHub mixes c++17 and c++20), counting ClCompile files; `"default"` when no `/std:` is set.
- `componentType` of a method = its class's `type`; for free functions `APPLICATION_ENTRY` (main/WinMain/DllMain) or `EXPORTED_API` (dllexport), as in `reference/tinypay-cpp-facts.json`; **`""` for other free functions** (no value is defined for them).
- `summary.dependencyCount` = number of `aggregatedDependencies`. `scanSeconds` is rounded to whole seconds.
- Unknown `ConfigurationType` (e.g. Utility): `packaging : "unknown"`, `deploymentRole : "UNCLEAR"`, listed under `unclearModules`.
- `database` and `integrations` are written empty (`migrationTool : "NONE"`) until T7.
- **Flow steps:**
  - a VIRTUAL call becomes one step per *defined* implementation (the interface method itself has no body to follow);
  - a call outside the solution becomes a step to `<targetClass>#<targetMethod>` (e.g. `#socket` now, `winsock#socket` after T7);
  - each method's calls are expanded once only, and later calls to it are steps but are not expanded again, so recursion and repeated helpers stay small.
- **Participants** are in first-appearance order (sequence-diagram order), not sorted.
- **Omitted:**
  - `logging` = target method or class name contains `log`/`trace`/`debug`. This catches `Logger::Instance()`, but could also catch e.g. `Catalog`.
  - `accessors` = one-line class method with no calls or branches that is a getter (no parameters, returns a value) or a setter (one parameter, returns void).
  - `std` = `std::` targets, except `std::thread`, which stays because it is an async start.

**Conflict:** the T6 "done when" asks for files identical apart from `generatedAt`, but `output-format.md` also asks for `summary.scanSeconds`, which changes with every run.

**Limitation:** the `MAIN App` flow stops at the `std::thread` start in `TcpHubServer::Run` (line 42). The thread function `&TcpHubServer::HandleClient` is passed as an argument, not called, so `HandleClient → HandleCommand → PaymentHubService::Process` (the actual payment flow) is not reachable from `main`. The full-plan reference handles this with a `LAMBDA`/`via` resolution. Not in the POC format.

---

## Task T7: Database facts and Winsock rule

**Status: DONE** (with the stub `sqlite3.h`, see T3)

**Database API check:** PayHub uses SQLite only (`sqlite3_*` from `<sqlite3.h>`); no other database API, so no question was needed.

**Command:** `java -jar target/cpp-scanner-poc.jar --solution D:\SampleCppCode\PayHub\PayHub.sln --out out\sample-facts.json --property SQLITE_DIR=d:\cpp-scanner-poc\src\test\resources\stubs\sqlite`. The new `--property NAME=VALUE` is passed to msbuild as `-p:NAME=VALUE`, so an SDK folder can be supplied without touching the environment or the solution.

**PayHub results:**

| Item | Value |
|---|---|
| `dataSources` | 3, all `sqlite:<dynamic>`. PayHub opens the database with a runtime path (`dbPath.c_str()` / `dbPath_.c_str()`), never a literal |
| SQL statements (exec/prepare) | 14: 11 resolved, 3 `<dynamic>` (`sql.c_str()` read from a file, `ok ? "COMMIT;" : "ROLLBACK;"`, the `Execute(const char* sql)` parameter) |
| `tables` | 5: `schema_version` (EMBEDDED_SQL, `MigrationRunner.cpp:87`) + `payments`, `payment_audit` (FK `end_to_end_id → payments.end_to_end_id`), `rail_config`, `participant_banks` from `db/migrations/*.sql`. Views and indexes are not tables |
| `migrationTool` / `migrationScripts` | `SQL_SCRIPTS`, the 5 `.sql` files |
| SQLite calls (`DATA_ACCESS`, `targetClass : "sqlite3"`) | 74 |
| Project calls marked `DATA_ACCESS` (one level) | 10, e.g. `PaymentHubService::Process → repository_.Save` = SAVE `[payments]` (through the VIRTUAL call's implementation) |
| `transactional` | `MigrationRunner::Apply` |
| `integrations.ipc` | 16 (SETUP 8, SERVER 3, CLIENT 1, IO 4) |
| `out/sql-review.md` | 14 statements, each marked resolved/dynamic, plus the 3 data sources |

Asserted in `PayHubT7Test` (runs the real scan in-process, about 40 s, skipped when PayHub or MSBuild is missing). The string-resolution steps PayHub doesn't contain are covered by `SqliteRuleTest` on an inline snippet with the stub header: a literal path, a `const char*` INSERT variable, a local `std::string`, a `constexpr` global, a `static constexpr` member, a `#define` with adjacent literals, and a runtime-built SELECT. SQL parsing is covered by `SqlTextTest`.

**Fixture gaps vs. the plan's T7 list:** PayHub has no `sqlite3_open` with a literal path, no INSERT held in a `const char*` variable, and no `db/schema.sql` (it has `db/migrations/V00x__*.sql`). These cases are covered by `SqliteRuleTest` instead.

**Bug found and fixed — macro extents:** for an expression coming from a macro, libclang's extent runs from the `#define` to the use. Tokenizing it returned half the file, which would have made `<dynamic>` `expression` values and condition texts garbage. Tokens are now taken between the expansion locations, so a macro use gives its name.

**Bug found and fixed — implicit operator=:** compiler-generated copy assignments (e.g. `RailLimits#operator=()`) were recorded as calls; now skipped like implicit constructors.

**Choices:**
- **Strings are resolved only as `output-format.md` lists.** Following a project wrapper is *not* done: `Execute("BEGIN TRANSACTION;")` → `sqlite3_exec(db_, sql, …)` is `<dynamic>` (parameter), so `SqlitePaymentRepository::Save` / `UpdateStatus` are not `transactional` and the `PRAGMA` is not seen.
- **Variables:** steps 2–3 apply when the argument is a variable name (also `A::B::name`) optionally followed by `.c_str()` / `.data()`; the initializer must be only string literals (`x = "..."`, `x("...")`, `x{"..."}`).
- **Operation of a statement:** the first statement with a known keyword (`BEGIN; INSERT…` is SAVE); `BEGIN`/`COMMIT`/`PRAGMA` have none, so no `operation`/`tables` keys.
- **One-level DATA_ACCESS:** the called function's first SQLite operation in seq order, with the tables of all its SQLite calls.
- **`tables` order:** EMBEDDED_SQL first (by file, line), then SQL files (by path, position), like the reference file groups entities before SQL. `line` is written for both sources. Module of a `.sql` file = the project whose folder contains it, else `""` (PayHub's `db/` is outside all projects).
- **`dataSources[].expression`** (the argument text) is added when the url is dynamic, as `output-format.md` asks; it is written after `client`.
- **Winsock:** every call declared in `winsock2.h` gets `kind : "IPC"`, `targetClass : "winsock"`; only the functions in the role table get an `ipc` entry (`htons`, `htonl` etc. don't).
- **Flow files:** SQLite calls after a `prepare`/`exec`/`open` step in the same function are folded into it, even when a project call sits in between (`prepare; while (step) { ParseRail(); column…; }`). Without that, result-reading loops flood the flow. DATABASE participant id = the url of the caller module's data source (`sqlite:<dynamic>` for PayHub); SOCKET participant id = `winsock`.
- **`.sql` files are read** (the plan asks for it) even though CLAUDE.md says to parse only C/C++. Only `CREATE TABLE` is extracted; hidden folders are skipped.

---

## Task T8: Run, measure, write findings

**Status: DONE, with three items pending people or tools outside the scanner** (developer review, LLM diagram, real target).

**Command (CLAUDE.md form):** `java -jar target/cpp-scanner-poc.jar --solution D:\SampleCppCode\PayHub\PayHub.sln --config Release --platform x64 --out out\facts.json --flows out\flows --max-depth 6 --property SQLITE_DIR=d:\cpp-scanner-poc\src\test\resources\stubs\sqlite`

**Target solution:** no target other than PayHub was given to this session, so PayHub was used. The run on the real target is **pending**; it needs a Developer environment, the target's third-party headers (or `--property` paths to them), and nothing else.

**Output:** `out/facts.json`, `out/flows/MAIN-App.json` (50 steps, 27 methods, 24 logging calls omitted, not truncated), `out/sql-review.md`, `out/review-sample.md`. Two runs: `facts.json` differs only in `generatedAt` and `scanSeconds`, the flow file only in `generatedAt`, and both `.md` files are identical.

**Speed (this machine, 20 files, one thread):**

| Step | Time |
|---|---|
| MSBuild evaluation, 6 projects | 3.6–3.8 s warm; 11.5 s on the first run after a build |
| Parse + extract | 33.6–36.3 s, **≈ 1.7 s per file** on average |
| Slowest files | ≈ 2.9–3.2 s: `main.cpp`, the Storage `.cpp` files, `storage/pch.cpp` |
| Scan total | 39 s warm (54 s cold) |

Almost all the time is libclang parsing the STL and Windows headers again for every file. Each project's `pch.cpp` is also parsed (it is a ClCompile item), which costs one extra parse per project. Options if a large target is too slow: parse files in parallel (libclang is thread-safe per index/TU), skip `pch.cpp`-only files, or use libclang's precompiled preamble. None was needed for PayHub.

**`out/review-sample.md`:** 20 of 316 calls and 10 of 23 classes, chosen with a fixed seed (`ReviewSample.SEED = 20261119`) from the sorted facts, so the same facts always give the same sample. Each row has the file and line, the function, the target, kind/resolution, `seq`, the control path and `async`, plus a tick box. Unit test: `ReviewSampleTest`.

**My pre-check of the sample (not a replacement for the developer's review):** every call row matches the source line, target, branch and order. For example, row 20 (`sqlite3_step` in `SqliteRailConfigRepository::Reload`) correctly has no control path because it is the `while` condition. Every class row matches its header.

**Sequence diagram:** not produced. The plan asks for the team's LLM tool, which the scanner session cannot reach, and drawing it myself would not test that tool. `out/flows/MAIN-App.json` is the only entry point and is ready to hand over; save the result as `out/sample-sequence-diagram.*`.

### libclang gaps (facts libclang could not give directly)

| Gap | Effect | Handled by |
|---|---|---|
| Overrides inside uninstantiated class templates are not reported | `ICache<K, V>::Get` lists no implementation in VIRTUAL `targetMethodIds` | Not handled (could be done by matching name + signature in the scanner) |
| `clang_CXXRecord_isAbstract` is false for class templates | `ICache` has no `abstract` annotation | Not handled; `type` is still `INTERFACE` (own rule) |
| `= default` special members are not definitions | They are not in `methods` | Accepted (no body, no calls) |
| An undeclared function call (missing header) is not a `CallExpr` | SQLite calls vanish without `sqlite3.h` | Needs the headers (`--property`); not a parser limit |
| Expression extents from macros run from the `#define` to the use | Wrong condition/expression text | Worked around: tokens between expansion locations |
| Compiler-generated constructors and `operator=` appear as calls | Noise calls | Filtered (implicit = same location as the class) |
| Members of a `dllexport` class all report the attribute | Wrong `DLL_EXPORT` entry points | Filtered (only functions marked themselves) |
| Class-template constructors are spelled `LruCache<K, V>` | Wrong method name | Stripped |
| `std::thread` constructor cursor turns into a template | Async rule missed it | Kind taken before the template swap |

**Conclusion:** everything the POC format asks for came from libclang's C API, with small workarounds. Only the template-override gap is a real loss, and it can be closed in the scanner without LibTooling. Nothing found so far justifies LibTooling.

---

## Questions & Conflicts Found

1. **Third-party headers for the fixture:** where should `sqlite3.h` and `librdkafka/rdkafka.h` come from on the scan machine (see T3)?
2. **Method IDs in anonymous namespaces collide** across files (`payhub::storage::(anonymous)#ParseRail(const std::string&)` twice). How should they be made unique? (T4)
3. **System calls:** keeping async system calls (thread starts, PostMessage) goes beyond "leave out calls into system headers except anchors"; confirm. (T5)
4. **`scanSeconds` vs determinism:** `summary.scanSeconds` changes every run, so facts.json can't be identical "apart from `generatedAt`". Drop it, fix it at 0, or accept two differing keys? (T6)
5. **Thread entry functions:** should the function handed to `std::thread` / `CreateThread` become a flow step, so the `MAIN` flow continues into the worker (`HandleClient` → `Process`)? (T6)
6. **SQL through project wrappers:** PayHub runs `BEGIN`/`COMMIT`/`PRAGMA` through `Execute(const char* sql)`, which the rules can only report as `<dynamic>`. Should literals passed to such a wrapper be followed one level? (T7)
7. **Real `sqlite3.h`:** the stub should be replaced by the real header (or an SDK path via `--property SQLITE_DIR=...`) on the target machine. (T3/T7)
8. **Flow file truncation:** `output-format.md` shows `truncated : false` in flow file examples, implying truncation is possible. No depth or size limit specified beyond `--max-depth`.
9. **Winsock anchor header:** the rule matches only declarations in `winsock2.h`. `getaddrinfo` and `freeaddrinfo` (`HttpRailConnector.cpp:50, 58, 63`) are declared in `ws2tcpip.h`, so they are neither IPC calls nor kept at all. Add `ws2tcpip.h` as a Winsock anchor? (T8)
10. **Real target run:** which solution, configuration and third-party header paths should the T8 run on the target use? (T8)
