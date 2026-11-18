# POC plan: tasks for Claude Code

Do the tasks in order. Each task ends with a "Done when" check. Report the evidence for it, then stop.

Success criteria for the POC as a whole. These are proposals; the team may adjust them before T0.

| Question | Pass |
| --- | --- |
| libclang via JavaCPP runs on this machine | A fixture file parses with no crash |
| Compiler settings are correct | ≥ 90% of the target solution's `.cpp` files parse without fatal errors |
| Extraction is correct | A C++ developer confirms ≥ 18 of 20 sampled calls and 10 of 10 sampled classes |
| Output format matches | Same top-level structure and key names as `docs/reference/tinypay-facts.json` |
| Pattern detection is feasible | Every SQLite and Winsock hit points to a real call, with the right file and line |
| Database facts are extractable | Data sources and tables match the code; the C++ developer confirms the SQL list in `out/sql-review.md`; the share of statements that are resolved rather than `dynamic` is recorded |
| Speed | Total time and per-file time recorded (no target) |
| Diagram-ready | Our LLM tool draws a correct sequence diagram for one entry point from its flow file, as judged by the C++ developer |
| libclang gaps | Every fact libclang could not provide is listed in `findings.md` (this decides whether LibTooling is ever needed) |

The output feeds an LLM that draws sequence and flow diagrams. Every call therefore carries its order (`seq`), its enclosing branches and loops (`controlPath`) and whether it is asynchronous. Each entry point also gets its own small flow file.

---

## T0: Environment check (≈ 1 hour, day 1)

Write `scripts/check-env.ps1`, which prints:
- the Java version;
- the Maven version;
- the `msbuild -version` output (from the Developer environment);
- whether `org.bytedeco:llvm-platform` resolves from Maven Central.

**Done when:** the script runs, Java is 17 or newer, and MSBuild is 17.8 or newer. The llvm-platform version to use is written in `docs/findings.md`.

## T1: Maven skeleton and first libclang parse (day 1, morning)

- Create a `pom.xml` with three dependencies: `llvm-platform` (newest on Maven Central), `jackson-databind` and JUnit 5. Build a fat jar named `cpp-scanner-poc.jar`.
- **Test fixture:** use the team's existing sample C++ solution (path given in the prompt). Do not modify it.
  - Check it against this list and report what it has and lacks:
    - two projects, one referencing the other;
    - a namespace;
    - a class hierarchy with a virtual method;
    - a `main` (or `WinMain`);
    - database code: an open call with a path, `CREATE TABLE` with primary and foreign keys, SQL held in a variable, SQL built at runtime, a `.sql` file;
    - Winsock calls;
    - a call inside an `if` and one inside a loop;
    - a `#pragma comment(lib, ...)`.
  - Missing items are recorded in `findings.md` as "not testable".
  - Only if no sample solution exists, create a minimal one under `src/test/resources/mini-solution/` that covers the list (with a stub `sqlite3.h` in its `third_party/`).
- In `parse/`, parse one fixture `.cpp` with hard-coded arguments and print libclang's diagnostics.

**Done when:** `mvn -q package` succeeds, and parsing the fixture prints zero fatal errors.

## T2: Compiler settings from MSBuild (day 1, afternoon)

- Read the project list from the `.sln`.
- The sample solution's static library holds the SQLite and Winsock code, and contains a `#pragma comment(lib, "ws2_32.lib")`.
- For each `.vcxproj`, run `msbuild -getItem:ClCompile` for the chosen configuration and platform, and parse the JSON.
- For each source file, build its libclang argument list as described in CLAUDE.md (technical notes).
- Unit test: given a saved `-getItem` JSON sample, the right argument list comes out.
- Also collect each project's dependencies, as described in `docs/output-format.md` (`modules[]`):
  - project references, via `msbuild -getItem:ProjectReference`;
  - `AdditionalDependencies` from the `<Link>` settings of the selected configuration in the `.vcxproj`;
  - `#pragma comment(lib, "...")` lines in the sources;
  - versions from `vcpkg.json` if present.

**Done when:** for the sample solution, the argument list printed for each file contains its defines, include dirs, standard and forced includes, and each project's dependency list is printed.

## T3: Parse the whole solution (day 1, end)

- Parse every ClCompile file of every project, one after another.
- Record any fatal or error diagnostics per file, and the time per file.
- Print a summary: files total, files clean, files with errors, total time, and the 5 slowest files.

**Done when:** you have run this on the target solution and reported the summary. ≥ 90% of files are clean, or the cause is written in `findings.md`.

## T4: Classes and functions (day 2, morning)

Read `docs/output-format.md`, section `modules[].components[]`, first. Use its field names from the start: `className`, `packageName`, `filePath`, `sourceFiles`, `type`, `annotations`, `baseClasses`, `dependencyTypes`.

- Walk each translation unit and skip system headers. Recurse into namespaces and nested classes.
- Collect:
  - classes and structs (`CLASS_DECL`, `STRUCT_DECL`, `CLASS_TEMPLATE`): USR, name, namespace (`packageName`, joined with `::`), header file, line, base classes with access and `virtual`, member field types;
  - functions and methods (`FUNCTION_DECL`, `CXX_METHOD`, `CONSTRUCTOR`, `DESTRUCTOR`, `FUNCTION_TEMPLATE`): USR, name, owning class, parameter types, return type, file, line, and the virtual, pure virtual, static and const flags.
- Keep only definitions (`clang_isCursorDefinition`), so forward declarations don't create empty classes.
- The owning class is the **semantic** parent (`clang_getCursorSemanticParent`), not the lexical one, so a method defined in a `.cpp` file still belongs to its class.
- Fill `sourceFiles` (the `.cpp` files where the class's methods are defined), `type` and `annotations` using the rules in `output-format.md`.
- `dependencyTypes` follow the spec's simplification rules (no pointers, references or `const`; smart pointers unwrapped; built-in and `std::` types left out except `std::thread` and `std::atomic<...>`). Parameter types keep their full spelling (for example `const std::string&`), because T5's method IDs need it.
- Remove duplicates by USR across all files.
- Assign each class to a module using the project file lists from T2, never a hard-coded path: the project whose folder contains its header, otherwise the first project whose files include it. All paths are relative to the solution folder, with forward slashes.

**Done when:** you report counts per project and list 3 hand-picked classes with their bases and members for a manual check. Choose classes that cover different cases: one with a base class and virtual methods, one in a namespace or nested in another class, and one from a different project than the other two. If one of these cases doesn't exist, say so; don't substitute an easier class.

## T5: Calls and entry points (day 2, afternoon)

- Inside each function body, collect call expressions: the referenced declaration, the call line, and resolution `VIRTUAL` if the target is a virtual method (otherwise `EXACT`).
- For flow and sequence diagrams, also record for each call:
  - `seq`: its order within the function body (source order, 1, 2, 3…);
  - `controlPath`: the enclosing `if` / `else` / `switch` / `case` / `for` / `while` / `do` / `try` / `catch` statements, outermost first, each with its condition text taken from the source tokens and which branch the call sits in;
  - `async`: true for thread starts (`CreateThread`, `_beginthreadex`, `std::thread`), `PostMessage` / `PostThreadMessage`, and socket `send` / `sendto`.
- For each function, also record:
  - its doc comment (`clang_Cursor_getBriefCommentText`), if any;
  - its `return` and `throw` points, each with its line and control path.
- Leave out calls into system headers, except the anchor-rule calls (T7).
- Entry points:
  - `main`, `wmain`, `WinMain`, `wWinMain`, `DllMain`;
  - functions marked `__declspec(dllexport)` (a DLLExport attribute child on the cursor).

**Done when:** you report counts of calls and entry points, and show the calls of `main` in the sample solution with `seq` and `controlPath`. They must match the source, and the fixture must contain at least one call inside an `if` and one inside a loop.

## T6: JSON in the tinypay format (day 3, morning)

- Write POJOs in `model/` and a Jackson writer in `output/` that follow `docs/output-format.md`.
- Unit test: the top-level keys of the output equal those of `docs/reference/tinypay-facts.json`, plus `sourceLanguage`, `schemaVersion`, `languages` and `diagnostics`, and the formatting style matches.

- Also write one flow file per entry point, `out/flows/<entry-point-id>.json`, as described in `docs/output-format.md`:
  - only the functions reachable from that entry point, up to `--max-depth` (default 6);
  - calls into logging functions, trivial getters/setters and the `std::` library left out;
  - ordered by `seq`.
- Output is deterministic: scanning the same code twice gives byte-identical files apart from `generatedAt`.

**Done when:** `out/sample-facts.json` and `one file in `out/flows/`` are written, the tests pass, and a second run produces identical files apart from `generatedAt`.

## T7: Database facts and Winsock rule (day 3, morning; about half a day)

Before starting, check which database API the target solution uses. If it isn't SQLite (for example ODBC or the MySQL C API), ask which header and functions to anchor on. Don't guess.

Database (SQLite), filled exactly as in `docs/output-format.md` ("database", "Database calls", "Resolving SQL and paths"):
- `database.dataSources` from `sqlite3_open*` calls.
- `database.tables` from `CREATE TABLE` statements in SQL text in the code and in `.sql` files in the solution folder; also set `migrationTool` and `migrationScripts`.
- On every call whose declaration is in `sqlite3.h`: `kind : "DATA_ACCESS"`, plus `sql`, `operation` and `tables` when the SQL can be resolved.
- On calls to project functions that directly perform a SQLite statement: `kind : "DATA_ACCESS"` with the same `operation` and `tables` (one level).
- No SQL parser library; simple keyword parsing is enough.

Winsock: when the called function's declaration is in a file ending with `winsock2.h`, add an `integrations.ipc` entry of type `TCP_SOCKET`.
- Role is `SERVER` for `bind`, `listen` and `accept`.
- Role is `CLIENT` for `connect`.
- Role is `IO` for `send` and `recv`.
- Each entry gets the `methodId` of the calling function, plus file and line.

The sample solution (T1) must therefore contain:
- a `sqlite3_open` with a literal path;
- a `CREATE TABLE` with a primary key and a foreign key;
- one `INSERT` held in a `const char*` variable;
- one `SELECT` built at runtime (expected: `"<dynamic>"`);
- a small `db/schema.sql`.

**Done when:**
- the sample solution's `database` section and `DATA_ACCESS` calls exactly match the expected values (asserted in a test), including the `"<dynamic>"` case;
- the Winsock entries match;
- on the target solution, a list of every SQL statement found is saved to `out/sql-review.md`, marked resolved or dynamic, for the C++ developer to check.

## T8: Run, measure, write findings (day 3, afternoon)

1. Run on the target solution and save `out/facts.json` and `out/flows/`.
   - Pick one important entry point and give its flow file to the team's LLM diagram tool.
   - Save the resulting sequence diagram as `out/sample-sequence-diagram.*` for the C++ developer to judge.
2. Generate `out/review-sample.md`: 20 random calls and 10 random classes (fixed random seed), each with file and line, ready for the C++ developer to tick.
3. Complete `docs/findings.md` from its template.

**Done when:** `facts.json`, `review-sample.md` and `findings.md` exist, and every success criterion is marked pass, fail or pending review.
