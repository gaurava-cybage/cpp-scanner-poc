# CLAUDE.md — context for Claude Code

## What this repo is
`PayHub` is a **synthetic test input** for a code-intelligence scanner I am building.
It is a small, generic payment hub (bank-side, account-to-account payments) written in C++ as a
Visual Studio solution. It is **not** a product and does not need to build or run.

`README.md` is the **ground truth**: every module, class, inheritance link, table, SQL script,
external library, cross-language dependency and execution flow a correct scan must find.
Keep README.md in sync with any code change.

## The scanner project (the real goal)
- A command-line scanner for C/C++ repositories that outputs **JSON facts** in a predefined schema.
- The JSON must closely match the facts JSON our existing in-house **Java** code-scanning tool produces.
- Facts to extract: modules, classes, functions, APIs, libraries, cache, messaging, dependencies,
  execution flow, build dependencies, database tables / SQL scripts.
- Detect dependencies on non-C/C++ components (Java services, Python scripts, Kafka/messaging) —
  only as dependencies of C/C++ modules. Non-C/C++ code itself is not scanned.
- Open-source libraries only; no commercial analysis tools. Lead suggested Java-based tooling
  (Eclipse CDT, tree-sitter Java bindings, ANTLR4, JavaCPP/LLVM bindings).
- Scanning must work **without building** the target code (parse source + read .vcxproj/.sln as XML;
  compile_commands.json is optional later).
- Output is JSON only — no UI or graphs.
- Target stacks: C++17/C++20, Visual Studio/MSVC, Win32/x64, Windows console apps, SQLite, Winsock.
  Qt 6 is deferred to a later phase. Keep phase 1 small.

## PayHub layout
```
PayHub.sln
db/migrations/   V001..V004 SQL scripts, applied at startup by MigrationRunner
db/queries/      ops_reports.sql (not referenced by code — intentional)
scripts/         sanctions_screen.py (called via CreateProcess)
src/Core         domain, interfaces, PaymentRouter, PaymentHubService (static lib, C++17)
src/Storage      SQLite repositories, MigrationRunner, LruCache (static lib, C++20)
src/Network      Winsock TCP server, HTTP rail connector, pacs.008 builder (static lib, C++20)
src/Messaging    Kafka publisher via librdkafka (static lib, C++17)
src/Integration  Python sanctions screener (static lib, C++17)
src/App          main.cpp console exe (C++20)
```
Namespaces: `payhub::core|storage|network|messaging|integration`.
`.vcxproj` files are generated consistently (same structure, ProjectReference for module deps).

## Rules when editing PayHub
- It is test data: add realistic complexity on purpose, and list every intentional "scanner trap"
  in README.md section 7.
- Keep names generic — no real vendor or product branding.
- Do not add a build requirement; external libs stay referenced via `$(SQLITE_DIR)` / `$(LIBRDKAFKA_DIR)`.
- New source files must be added to the right `.vcxproj` and to README.md.
- Zip files are not allowed in my organization — never package output as .zip.

## Planned next additions (complexity for scanner testing)
1. ✅ **DONE** — Precompiled header (`pch.h` / `pch.cpp`) in each project. Added to Core, Storage, Network, Messaging, Integration, App. Configured in `.vcxproj` with `<PrecompiledHeader>Use</PrecompiledHeader>` and `<PrecompiledHeader>Create</PrecompiledHeader>`.
2. ✅ **DONE** — `#pragma pack` struct for binary message format (`WireProtocol.h`); mixed `#pragma once` vs `#ifndef` guards (WinsockSession.h, Pacs008Builder.h use `#ifndef`).
3. ✅ **DONE** — Heavier macros: `PaymentMacros.h` with `__declspec(dllexport)`, function-like macros `DECLARE_INTERFACE_METHOD()`, `IMPLEMENT_PROPERTY()`, etc. Applied to `Logger` class.
4. ✅ **DONE** — Shared `.props` property sheet: `PayHub.Common.props` imported by all 6 `.vcxproj` files. Defines common output paths, debug/release configs, compile settings.
5. ✅ **DONE** — A config file (`appsettings.json`) read at startup. JSON parsed inline in `main.cpp::LoadConfigFile()`.
6. A googletest unit-test project (`PayHub.Tests`).

## Next step for the scanner itself
Write the expected JSON facts for PayHub in our Java tool's schema, so scanner output can be diffed
against it automatically.