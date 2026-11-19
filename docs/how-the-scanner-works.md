# How the C++ Scanner Works

**Written for:** managers and non-technical readers.

## 1. The one-paragraph version

The scanner reads a Visual Studio C++ solution, which is the folder of files that makes up a software product. It does not build, run or change the product. It reads the source code the way a compiler does and writes down what it finds: the parts of the system, which parts depend on which, which function calls which and in what order, and where the system talks to a database or to the network. The result is a single structured file, `facts.json`, which an AI tool can then turn into diagrams.

## 2. What goes in and what comes out

| | |
|---|---|
| **Input** | One Visual Studio solution (`.sln`), read only |
| **Output** | `facts.json`: the full picture of the system. `flows\` folder: one small file per starting point of the program, for drawing a sequence diagram. Two review files for a developer to check the results by hand |
| **Changes to the product** | None. Nothing is compiled, built, cleaned or restored |
| **Time on the sample system** | About 40 seconds for 20 source files |

## 3. The steps, in order

The scanner works in six steps. Each step feeds the next.

**Step 1: Find the projects.**
It opens the solution file and lists the projects inside it, such as the core logic, the database layer, the network layer and the main application.

**Step 2: Ask Visual Studio's own tool how each project is set up.**
For each project, the scanner asks Microsoft's build tool (MSBuild) a question: "which source files do you use, and with which settings?" This is a read-only query. MSBuild only reads the project description and reports back. It does **not** compile the code. The answer includes:
- the list of source files;
- the settings that change how the code is read, such as preprocessor definitions and the C++ language version;
- which other projects this one depends on;
- whether the project is a program, a library, or something else.

**Step 3: Read each source file with the same parser a compiler uses.**
The scanner uses libclang, the open-source parser behind the LLVM/Clang compiler, to read every C or C++ file. It reads the file with the same settings Visual Studio would use, so it understands the same code. Only C and C++ files are read: `.c`, `.cpp`, `.cc`, `.cxx`, `.h`, `.hpp`. Other file types are ignored.

**Step 4: Collect the facts.**
From each file's structure the scanner records:
- **Parts of the system (classes):** their name, their area (namespace), their file, what they inherit from, and the kinds of data they hold.
- **Functions:** their name, which part they belong to, their inputs and outputs, and whether they can be overridden.
- **Calls:** every time one function calls another, recorded with:
  - its **order** inside the function (1, 2, 3, ...);
  - the **conditions** around it, such as "only if the payment is valid" or "inside a loop";
  - whether it runs **in the background** (for example a new thread or a network send).
- **Starting points:** the functions where the program begins, such as `main`.

**Step 5: Find the databases and the network.**
- **Database:** every call to SQLite (the database library) is recorded with the SQL statement it runs, the operation (read, save, delete) and the tables it touches. Table definitions come from the code and from the migration scripts (`.sql` files).
- **Network:** every call to the Windows network library (Winsock) is recorded with its role: server, client, sending, receiving or setup.

**Step 6: Write the results.**
Everything is sorted in a fixed order, so running the scanner twice on the same code gives the same result. The only differences between runs are the timestamp and the scan time. The results are written to `facts.json`, one flow file per starting point, and the two review files.

## 4. How it finds the dependencies

"Dependency" here means "this part needs that part to work." The scanner looks in four places:

1. **Other projects in the same solution.** If project A is set up to use project B, A depends on B.
2. **Libraries named in the project's link settings.** These are the external libraries the project is told to link against, such as the SQLite library or the network library. They are read from the project file itself, for the chosen build configuration (for example Release, x64).
3. **Libraries named inside the source code.** Some code says "link this library" directly with a special instruction. The scanner finds these in the source and header files.
4. **Version information.** If the project uses a package list file (`vcpkg.json`), the scanner reads the library versions from it.

Each dependency is marked with its origin (for example "from link settings" or "from source code") so that a reviewer can see where it came from.

## 5. What the scanner skips, and why

Skipping is deliberate. Without it, the output would be full of noise: standard library internals, Windows system headers and compiler-generated code. Here are the main skip decisions:

| What is skipped | Why |
|---|---|
| **Files that are not C or C++** | The scanner is only meant for C and C++ code |
| **Windows and compiler system files** | They describe the platform, not the product's own design. Only the few calls that matter for the picture are kept, such as the network and background-thread calls |
| **Third-party library code outside the solution folder** | Otherwise every external library would appear as part of the product. Calls into those libraries are still recorded, but their internal classes are not |
| **Include folders that do not exist on this machine** | They are listed on screen and skipped, so the scan can continue |
| **Compiler-generated code** (default constructors, automatic copy operations) | Nobody wrote these, so they are not design decisions |
| **Calls to a local helper function (lambda)** | They are part of the enclosing function, so they are counted there |
| **Duplicate code seen from several files** | A class or function that appears in many files (for example in a header) is recorded once |
| **Logging calls** (in the flow files only) | They appear everywhere and would hide the business flow. They remain in `facts.json` |
| **Simple getters and setters** (in the flow files only) | Trivial, and not part of the process |
| **Standard library calls** (in the flow files only) | Except background-thread starts, which are kept because they matter for the picture |
| **SQL that cannot be read** | It is marked `<dynamic>` rather than guessed. The scanner never invents SQL |
| **Hidden folders** when looking for `.sql` files | Standard practice; they are not part of the product |

## 6. What happens when something goes wrong

| Situation | What the scanner does | Effect on the result |
|---|---|---|
| **A source file is missing a third-party header** (for example the SQLite header is not installed) | Keeps reading the rest of the file and records the error | The file is still analysed, but calls to that library are missing. The error is listed in the output |
| **A file cannot be read at all** | Skips that file and records the reason | That file is missing from the result; all other files are still analysed |
| **Visual Studio's build tool cannot be found or fails on a project** | Stops the scan with a clear error message and exit code 1 | No result is written. The problem must be fixed first (usually by running it from the Visual Studio Developer prompt) |
| **An SQL statement cannot be read** (built at runtime) | Marks it `<dynamic>` | The statement is still listed, so a developer can check it by hand |
| **A database path cannot be read** | Marks it `<dynamic>` | Same as above |
| **A project type is unknown** | Marks the project as "unclear" | A developer must decide what it is |
| **Some of the code does not compile on this machine** | The scan still runs | The number of "clean" files drops; the reason is shown in the output |

In short: **the scanner never stops for a single broken file.** It stops only when the whole solution cannot be read. Everything it could not read is listed, so nothing disappears silently.

## 7. What the scanner does not do yet

These are honest limits of the proof of concept:

- **Windows only.** It relies on Visual Studio and the Windows build settings.
- **Libraries set in shared settings files may be missed.** Some projects take their library list from imported settings files; the scanner reads only the project's own file.
- **Background threads are not followed.** When the program starts a new thread, the scanner records the start, but not the work done in that thread. The main business flow can therefore stop at the thread start.
- **Some template code is incomplete.** Overrides inside certain generic (template) classes are not reported.
- **Only the SQLite database library is understood.** Other database libraries would need their own rules.
- **Only the Windows Winsock network library is understood.** Some network calls in a second header (`ws2tcpip.h`) are not recorded yet.
- **SQL passed through a helper function is not followed.** If a wrapper function receives the SQL text as a parameter, the statement is shown as `<dynamic>`.
- **The SQLite header on the sample machine is a stub.** The real header should be used on the real target.

Each of these is written in `docs/findings.md` with its open question.

## 8. How the results were checked

- **Automated tests:** 33 tests check the parts of the logic, including a full scan of the sample system. They run on every build.
- **Format check:** a test confirms the output has the same structure and field names as the reference file.
- **Repeat runs:** two runs on the same code give the same result.
- **Hand checks:** 20 random calls and 10 random classes were checked against the source code. All 30 matched. A C++ developer still has to confirm them independently (`out\review-sample.md`).
- **SQL review:** every SQL statement is listed for a developer to confirm (`out\sql-review.md`).

## 9. Results on the sample system

The sample system is the team's PayHub test solution, with 6 projects and 20 source files.

| Measure | Result |
|---|---|
| Source files read cleanly | 19 of 20 (95%) |
| Parts of the system (classes) | 23 |
| Functions | 88 |
| Calls recorded | 316 |
| Starting points | 1 (`main`) |
| Databases | 3 |
| SQL statements | 14, of which 11 were read fully and 3 are marked as runtime-built |
| Database tables | 5 |
| Network entries | 16 |
| Scan time | about 40 seconds |

## 10. Decisions still open

These need a decision from the team before the next phase. Each is explained in `docs/findings.md`:

1. Should functions with the same name in different files, inside private code blocks, be given unique identifiers?
2. Should background threads be followed, so the main flow continues into the worker?
3. Should the scanner count the network helper header as a network source?
4. Should SQL passed through a helper function be followed?
5. Which solution, settings and header folders will be used on the real target?
6. Should the flow files be limited in size, and how?

## 11. Suggested presentation for top management

Five slides are enough:

1. **The problem:** the team cannot see, quickly and reliably, how the C++ system fits together: who calls whom, where the data goes and where the network is used.
2. **What the scanner does:** it reads the code without changing it and writes a map of the system in one structured file. Show the six steps from section 3 in plain words.
3. **What it found on the sample system:** the numbers from section 9, plus one example: "the payment is validated, screened, routed and saved to the database", as a simple picture.
4. **How far we can trust it:** the checks in section 8, the limits in section 7, and what happens when something goes wrong (section 6). Be explicit that a developer still confirms the results.
5. **What we need next:** the decisions in section 10, the real target run, and an agreed date for the developer review.

**Key message to leave with management:** the scanner reads the code safely and records what it cannot read instead of guessing, so the team can trust what it reports and see exactly where the gaps are.
