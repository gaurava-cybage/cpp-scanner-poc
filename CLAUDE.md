# C/C++ Scanner — POC

A throwaway proof of concept, to be built in 2–3 days. It is a Java command-line tool that:
- reads **one** Visual Studio C++ solution;
- parses its C/C++ code with **libclang** (through JavaCPP);
- writes the code's structure as `facts.json` in the same format as `docs/reference/tinypay-facts.json`.

The output is later given to an LLM that draws sequence and flow diagrams. Every call therefore records:
- its order (`seq`);
- the branches and loops around it (`controlPath`);
- whether it is asynchronous (`async`).

Each entry point also gets its own small flow file under `out/flows/`.

The full task list is in `docs/poc-plan.md`. The output format is in `docs/output-format.md`.

## Hard constraints

- **Language and build:** Java 17, Maven, single module. No Spring, no frameworks.
- **Allowed libraries:** `org.bytedeco:llvm-platform` (libclang) and `com.fasterxml.jackson.core:jackson-databind`. Also JUnit 5 for tests. **Ask before adding any other library.**
- **Scan scope:** C and C++ only (`.c`, `.cpp`, `.cc`, `.cxx`, `.h`, `.hpp`). Never parse other languages.
- **Read-only:** the target solution is never modified, built, cleaned or restored by the scanner.
- **Platform:** Windows only. Compiler settings come from MSBuild (`msbuild -getItem`), which must run from a Visual Studio Developer environment.
- **Which document wins:** `docs/output-format.md` is the authority for the POC output. `docs/reference/tinypay-facts.json` (Java) defines the overall structure. `docs/reference/tinypay-cpp-facts.json` is an illustrative example that also shows full-plan sections the POC does not fill. If the files disagree, follow `output-format.md`.
- **Output format:** must match the reference file's top-level structure, key names and formatting. Use Jackson's `DefaultPrettyPrinter` style: `"key" : value`, two-space indent, `[ {` for arrays of objects.
- **Throwaway code:** prefer simple, readable code over abstractions. No interfaces with one implementation, no plugin systems.

## Commands

```bash
mvn -q package                                   # build target/cpp-scanner-poc.jar (fat jar)
mvn -q test                                      # unit tests
java -jar target/cpp-scanner-poc.jar --solution <path\to\App.sln> --config Release --platform x64 --out out\facts.json --flows out\flows --max-depth 6
java -jar target/cpp-scanner-poc.jar --solution <path\to\Sample.sln> --out out\sample-facts.json   # the team's sample solution = test fixture
```

## Project layout

```
CLAUDE.md
docs/
  poc-plan.md             tasks T0–T8 with "done when" checks (work through them in order)
  output-format.md        JSON shape, ID formats, enum values
  findings.md             written during the POC (results, fallbacks used, open questions)
  reference/
    tinypay-facts.json      Java scanner output = the format contract
    tinypay-cpp-facts.json  hand-written C++ example of the target shape
src/main/java/poc/cppscan/
  Main.java               argument parsing (plain args, no CLI library), orchestration, timing
  msbuild/                run msbuild -getItem, turn ClCompile items into per-file clang args
  parse/                  libclang wrapper: parse one file, collect diagnostics
  extract/                classes, functions, calls, entry points (visitor over the AST)
  rules/                  database facts (SQLite: data sources, tables, SQL on calls) and the Winsock rule
  model/                  plain POJOs mirroring the JSON
  output/                 Jackson writer
src/test/java/poc/cppscan/
(test fixture = the team's existing sample solution, outside this folder; never modified)
out/                      scan results (git-ignored)
```

## Working rules

1. Work on **one task** from `docs/poc-plan.md` at a time, in order. Before planning a task, read the sections of `docs/output-format.md` it produces, and use its field names and rules. Do not ask questions that file already answers.
2. At the end of each task, stop and report the evidence its "Done when" line asks for. Wait for a go-ahead before starting the next task.
3. After every change, run `mvn -q package` and scan the sample solution. Do not report a task done if either fails.
4. When unsure about a JavaCPP class or method name, check it in the jar (for example `javap -cp <jar> org.bytedeco.llvm.global.clang | findstr clang_visit`). Do not guess.
5. Write every assumption, fallback and surprise into `docs/findings.md` as you go.
6. Never print or log the target solution's source code beyond file names, line numbers and symbol names.
7. **No sample-specific values in `src/main`.** The scanner must work on any `.sln`. Never hard-code project names, file names, paths, class names or counts from the sample solution; derive everything at runtime from the `.sln`, the `.vcxproj` files and the parsed code. Sample-specific values belong only in `src/test`, as expected results. Key files by their path relative to the solution folder, never by bare file name.

## Technical notes

- **libclang arguments for MSVC code:**
  - always: `--driver-mode=cl`, `-fms-compatibility`, `-fms-extensions`;
  - from each ClCompile item: `/D` defines, `/I` include dirs, `/std:` from `LanguageStandard`, and `/FI` for forced includes and precompiled headers.
  - Pass the PCH header (`pch.h` or `stdafx.h`) as a forced include. Never use the `.pch` file.
- **Compiler settings:** use `msbuild <proj>.vcxproj -getItem:ClCompile -p:Configuration=<c> -p:Platform=<p>`. It returns JSON with each source file and its evaluated metadata (needs MSBuild 17.8 or later). Run it per `.vcxproj`, with the project list taken from the `.sln`.
- **System headers:** skip cursors there (`clang_Location_isInSystemHeader`).
- **Duplicates:** remove them by `clang_getCursorUSR`. A class seen from many `.cpp` files is one component.
- **STL1000 error ("unexpected compiler version"):** first try a newer `llvm-platform` release. As a POC-only fallback, add `/D_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH` and record that in `findings.md`.
- **Native crash in libclang on a file:** log the file name, skip it and continue. Worker processes are out of scope for the POC.
- **Control path:** while walking a function body, keep a stack of enclosing `IfStmt` / `SwitchStmt` / `CaseStmt` / `ForStmt` / `CXXForRangeStmt` / `WhileStmt` / `DoStmt` / `CXXTryStmt` / `CXXCatchStmt` cursors.
  - Take the condition text from the source tokens (`clang_tokenize` over the condition's extent).
  - For an `if`, work out the branch from which child statement contains the call.
- **Determinism:** sort every list as `docs/output-format.md` specifies. Never iterate a `HashMap` into the output; use sorted or insertion-ordered collections.
- **Anchor rules:** a call is a SQLite or Winsock call when the *declaration* of the called function is in a file ending with `sqlite3.h` or `winsock2.h`. Never match by function name alone.
- **Database facts:**
  - Fill `database` in the same shape as the reference file.
  - Record database access on the calls (`kind : "DATA_ACCESS"` with `sql`, `operation`, `tables`). There is no separate list of database calls.
  - Resolve SQL strings and DB paths as described in `docs/output-format.md`; use `"<dynamic>"` when they can't be resolved. Never invent SQL.
  - If the target solution uses a database API other than SQLite, ask before writing rules for it.
