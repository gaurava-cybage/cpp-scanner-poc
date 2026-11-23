# C/C++ Scanner (POC)

A command-line tool that reads one Visual Studio C++ solution and writes its structure as `facts.json`, in the same format as the Java reference file. It also writes one flow file per program entry point, for drawing sequence diagrams.

The scanner is **read-only**. It never builds, cleans, restores or changes the solution.

For the plain-language explanation, see [docs/how-the-scanner-works.md](docs/how-the-scanner-works.md). Results and open questions are in [docs/findings.md](docs/findings.md).

## Requirements

| Requirement | Version | Notes |
|---|---|---|
| Windows | 10 or 11 | The tool is Windows only |
| Java | 17 or newer | JDK 17 is tested |
| Maven | 3.9 or newer | Only needed to build the jar |
| MSBuild | 17.8 or newer | Run from a *Visual Studio Developer* prompt, or install Visual Studio so the tool can find MSBuild with `vswhere` |
| Third-party headers | as needed | For example `sqlite3.h`. Point to them with `--property` (see below) |

Maven downloads the libraries on the first build: libclang (`org.bytedeco:llvm-platform`), Jackson and JUnit 5. No other libraries are used.

## Build

```powershell
mvn -q package
```

This creates `target\cpp-scanner-poc.jar` (a fat jar, about 590 MB because it contains the Windows libclang binaries). It also runs the unit tests. A full build takes a few minutes, mostly because one test scans the sample solution.

To run only the tests:

```powershell
mvn -q test
```

## Run

```powershell
java -jar target\cpp-scanner-poc.jar --solution <path\to\App.sln> --config Release --platform x64 --out out\facts.json --flows out\flows --max-depth 6
```

Example with the team's sample solution, which needs the SQLite header folder:

```powershell
java -jar target\cpp-scanner-poc.jar --solution D:\SampleCppCode\PayHub\PayHub.sln --config Release --platform x64 --out out\facts.json --flows out\flows --max-depth 6 --property "SQLITE_DIR=d:\cpp-scanner-poc\src\test\resources\stubs\sqlite"
```

### Options

| Option | Default | Meaning |
|---|---|---|
| `--solution <path>` | required | The `.sln` file to scan |
| `--config <name>` | `Release` | Build configuration to read settings for |
| `--platform <name>` | `x64` | Build platform to read settings for |
| `--out <file>` | `out\facts.json` | Where `facts.json` is written |
| `--flows <dir>` | `flows` folder next to `--out` | Where flow files are written. Existing `*.json` files there are deleted first, so use a folder for this scanner only |
| `--max-depth <n>` | `6` | How deep each flow file follows calls |
| `--property NAME=VALUE` | none | Passes an MSBuild property, for example a header folder such as `SQLITE_DIR`. Can be repeated |
| `--print-args` | off | Prints the compiler arguments used for each file |
| `--details` | off | Prints class and function details to the console |
| `--show-calls <text>` | off | Prints the calls of matching methods to the console |

## Output

Everything is written next to `--out` (the `review` files) or into `--flows`:

| File | What it is | Who uses it |
|---|---|---|
| `facts.json` | The full picture: modules, dependencies, classes, methods, calls with order and conditions, database and network facts, diagnostics | The LLM tool; the reference format |
| `flows\<entry-point>.json` | Calls from one program entry point, in order | The sequence-diagram tool |
| `sql-review.md` | Every SQL statement found, with a tick box | The C++ developer |
| `review-sample.md` | 20 random calls and 10 random classes (fixed seed), with a tick box | The C++ developer |

Two runs on the same code give the same files, except for `generatedAt` and `scanSeconds`.

## Output and errors

- **Missing headers** do not stop the scan. The file is still analysed; the error is listed in `diagnostics` and on screen. Calls to a missing library are then absent. Provide the header with `--property`.
- **Include folders that do not exist** are skipped and printed.
- **A file libclang cannot read** is dropped and reported.
- **MSBuild not found, or failing on a project** stops the scan with an error and exit code 1. Nothing is written.
- **SQL that cannot be read** is written as `<dynamic>`. The scanner never guesses SQL.

## Tests

```powershell
mvn -q test
```

33 tests cover the MSBuild reader, dependency detection, the extractor, call order and control paths, the output format, SQL parsing and the SQLite and Winsock rules. `PayHubT7Test` runs a full scan of the sample solution and is skipped when the sample or MSBuild is not present.

## Project layout

```
src/main/java/poc/cppscan/
  Main.java        command line, orchestration, timing
  msbuild/         MSBuild queries, dependency detection, solution reading
  parse/           libclang wrapper and the parse step for all files
  extract/         classes, functions, calls, entry points
  rules/           SQLite, SQL text, SQL files, Winsock
  model/           records that mirror the JSON
  output/          fact and flow builders, JSON writer, review files
src/test/          unit tests and the stub SQLite header (stubs/sqlite/include)
docs/              plan, output format, findings, plain-language explanation
reference/         the Java and C++ example files used as the format reference
scripts/           check-env.ps1: checks Java, Maven and MSBuild versions
```

## Environment check

```powershell
powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1
```

Prints the Java, Maven and MSBuild versions, and whether the libclang package resolves.

## Limits

- Windows only.
- One solution per run.
- A library set only in an imported `.props` file is not found.
- Background threads are recorded as starts; the work inside them is not followed.
- Only SQLite and Winsock are recognised as database and network APIs.
- SQL passed through a helper function is shown as `<dynamic>`.

The full list of open questions is in [docs/findings.md](docs/findings.md).
