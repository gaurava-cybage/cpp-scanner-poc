# PayHub — sample payment hub for scanner testing

A small, generic **payment hub** written in C++ (Visual Studio solution, Windows console app, Win32/x64).
It accepts credit-transfer instructions from banking channels, validates them, runs sanctions
screening, routes each payment to a clearing rail (IMPS / NEFT / RTGS / SWIFT) using rail limits and
bank participation from reference data, submits it as an ISO 20022 pacs.008 message, records it in
SQLite with an audit trail, and publishes events to Kafka. The database schema is managed by
versioned SQL migration scripts applied at startup.

This project exists **only as test input for the code-intelligence scanner**. It is not meant to be
built or run; external libraries (SQLite, librdkafka) are referenced through `$(SQLITE_DIR)` and
`$(LIBRDKAFKA_DIR)` but not included.

Below is the **ground truth**: what a correct scan should find.

---

## 1. Modules (Visual Studio projects)

| Module | Type | C++ std | Depends on (ProjectReference) | External libs |
|---|---|---|---|---|
| Core | Static lib | C++17 | — | — |
| Storage | Static lib | C++20 | Core | SQLite (`sqlite3.h`), `std::filesystem`; SQL scripts in `db/migrations` (listed as `<None>` items) |
| Network | Static lib | C++20 | Core | Winsock (`winsock2.h`, `ws2tcpip.h`, `ws2_32.lib`) |
| Messaging | Static lib | C++17 | Core | librdkafka (`librdkafka/rdkafka.h`) |
| Integration | Static lib | C++17 | Core | Win32 API (`windows.h`) |
| App | Console exe | C++20 | Core, Storage, Network, Messaging, Integration | `sqlite3.lib`, `ws2_32.lib`, `librdkafka.lib` |

Namespaces: `payhub::core`, `payhub::storage`, `payhub::network`, `payhub::messaging`, `payhub::integration`.

## 2. Classes and inheritance

| Class | Module | Kind | Implements / derives from |
|---|---|---|---|
| `Payment` | Core | Domain entity | — |
| `PaymentRouter` | Core | Pure logic | — |
| `PaymentHubService` | Core | Orchestrator | — |
| `Logger` | Core | Singleton | — |
| `IPaymentRepository` | Core | Interface | — |
| `ICache<K, V>` | Core | Interface (template) | — |
| `IEventPublisher` | Core | Interface | — |
| `IRailConnector` | Core | Interface | — |
| `ISanctionsScreener` | Core | Interface | — |
| `IRailConfigRepository` | Core | Interface | — |
| `RailSubmission` | Core | Struct | — |
| `RailLimits` | Core | Struct | — |
| `SqlitePaymentRepository` | Storage | Implementation | `core::IPaymentRepository` |
| `SqliteRailConfigRepository` | Storage | Implementation (in-memory reference data) | `core::IRailConfigRepository` |
| `MigrationRunner` | Storage | Schema migration | — |
| `LruCache<K, V>` | Storage | Implementation (header-only template) | `core::ICache<K, V>` |
| `WinsockSession` | Network | RAII wrapper | — |
| `Pacs008Builder` | Network | Message builder | — |
| `HttpRailConnector` | Network | Implementation | `core::IRailConnector` |
| `TcpHubServer` | Network | Server | — |
| `KafkaEventPublisher` | Messaging | Implementation | `core::IEventPublisher` |
| `PythonSanctionsScreener` | Integration | Implementation | `core::ISanctionsScreener` |

Enums: `Priority`, `Rail`, `PaymentStatus` (Core), `LogLevel` (Core).
Macros: `PHUB_LOG_INFO`, `PHUB_LOG_WARN`, `PHUB_LOG_ERROR` (Logger.h).

## 3. Infrastructure facts

| Category | Fact | Where |
|---|---|---|
| Configuration | JSON config file `appsettings.json` (database path, Kafka brokers, rail connector host/port, bank BIC, Python script path, channel port, cache capacity) | `main.cpp` reads at startup via `LoadConfigFile()` |
| Macros | `PaymentMacros.h`: `PAYHUB_EXPORT`, `PAYHUB_IMPORT` (`__declspec` on Windows); function-like macros `DECLARE_INTERFACE_METHOD()`, `IMPLEMENT_GETTER/SETTER()`, `BEGIN_INTERFACE/END_INTERFACE` | `Logger.h` applies `PAYHUB_EXPORT`; `PaymentMacros.h` |
| Build inheritance | Shared `PayHub.Common.props` imported by all 6 `.vcxproj` files; defines common output paths, debug/release configurations, compile settings | `PayHub.Common.props`, `src/*/\*.vcxproj` |
| Database | SQLite file `payhub.db`, WAL mode, schema from `db/migrations` (see section 4) | `MigrationRunner.cpp`, `SqlitePaymentRepository.cpp`, `SqliteRailConfigRepository.cpp` |
| Cache | In-process LRU cache, `std::string → Payment`, capacity 50000 | `LruCache.h`, wired in `main.cpp` |
| Messaging | Kafka producer, topics `payments.submitted`, `payments.settled`, `payments.rejected` | `KafkaEventPublisher.cpp`, `IEventPublisher.h` |
| Inbound API | TCP server (Winsock) port 6060, commands `SUBMIT`, `SETTLED`, `STATUS` | `TcpHubServer.cpp` |
| Message format | ISO 20022 pacs.008.001.08 XML; wire protocol `WireMessageHeader` / `PaymentSubmitMessage` with `#pragma pack(1)` for binary serialization | `Pacs008Builder.cpp`, `WireProtocol.h` |
| Threading | `std::thread` per connection, `std::mutex`, `std::atomic` | `TcpHubServer.cpp`, `SqlitePaymentRepository.cpp`, `LruCache.h` |
| Platform branch | `#ifdef _WIN32` (CreateProcess) / `#else` (popen) | `PythonSanctionsScreener.cpp` |

## 4. Database

**Migration scripts** (`db/migrations`, applied in version order by `MigrationRunner` at startup and
recorded in `schema_version`):

| Script | Creates |
|---|---|
| `V001__create_payment_tables.sql` | tables `payments`, `payment_audit`; indexes `ix_payments_status`, `ix_payments_rail_created`, `ix_payments_debtor`, `ix_payment_audit_e2e` |
| `V002__create_reference_data_tables.sql` | tables `rail_config`, `participant_banks` |
| `V003__create_reporting_views.sql` | views `v_pending_settlement`, `v_daily_rail_summary` |
| `V004__seed_reference_data.sql` | 4 rows in `rail_config`, 5 rows in `participant_banks` |

`db/queries/ops_reports.sql` holds manual support queries. No code references it.

**Tables and who uses them**

| Table / view | Defined in | C++ reads | C++ writes |
|---|---|---|---|
| `payments` | V001 | `SqlitePaymentRepository::FindById` | `SqlitePaymentRepository::Save`, `UpdateStatus` |
| `payment_audit` | V001 | — | `SqlitePaymentRepository::AppendAudit` |
| `rail_config` | V002 (+ V004 data) | `SqliteRailConfigRepository::Reload` | — |
| `participant_banks` | V002 (+ V004 data) | `SqliteRailConfigRepository::Reload` | — |
| `schema_version` | **C++ only** (`MigrationRunner::EnsureVersionTable`) | `MigrationRunner::CurrentVersion` | `MigrationRunner::Apply` |
| `v_pending_settlement` | V003 | — (used by Java `ops-reporting-service` and `ops_reports.sql`) | — |
| `v_daily_rail_summary` | V003 | — (used by Java `ops-reporting-service` and `ops_reports.sql`) | — |

## 5. Cross-language dependencies (detect, do not scan)

| Dependency | Language | How C++ reaches it | Where |
|---|---|---|---|
| `rail-connector-service` | Java (Spring Boot) | HTTP POST `/api/v1/rails/{rail}/submit`, localhost:8085, XML body | `HttpRailConnector.cpp` |
| `sanctions_screen.py` | Python | Child process via `CreateProcessA` | `PythonSanctionsScreener.cpp`, `scripts/` |
| `accounting-service` | Java | Kafka consumer of `payments.settled` | Comment in `KafkaEventPublisher.h` |
| `customer-notification-service` | Java | Kafka consumer of all payment topics | Comment in `KafkaEventPublisher.h` |
| `ops-reporting-service` | Java | Reads SQL views `v_pending_settlement`, `v_daily_rail_summary` | Comment in `V003__create_reporting_views.sql` |

## 6. Execution flows

**Startup** (`main`)
```
main
 ├─ WinsockSession::WinsockSession            → WSAStartup
 ├─ MigrationRunner::Run                       → EnsureVersionTable → CurrentVersion → FindScripts
 │                                             → Apply (per new script: ReadFile, sqlite3_exec)
 ├─ SqlitePaymentRepository::SqlitePaymentRepository
 ├─ SqliteRailConfigRepository::SqliteRailConfigRepository → Reload (rail_config, participant_banks)
 ├─ PaymentRouter, PaymentHubService, KafkaEventPublisher, Pacs008Builder, HttpRailConnector, PythonSanctionsScreener
 └─ TcpHubServer::Run
```

**Submit payment** (`TcpHubServer::HandleCommand` "SUBMIT")
```
TcpHubServer::HandleCommand
 └─ PaymentHubService::Process
     ├─ ICache::Get                      (duplicate check)        → LruCache::Get
     ├─ PaymentHubService::Validate
     ├─ ISanctionsScreener::Screen       → PythonSanctionsScreener::Screen → RunScript → CreateProcessA (Python)
     ├─ PaymentRouter::SelectRail        → IsDomestic, IsEligible
     │                                     → IRailConfigRepository::GetLimits / BankSupportsRail
     │                                     (Unassigned → Reject "no eligible rail")
     ├─ IRailConnector::Submit           → HttpRailConnector::Submit → Pacs008Builder::Build
     │                                                               → HttpRailConnector::HttpPost (Java service)
     ├─ IPaymentRepository::Save         → SqlitePaymentRepository::Save → AppendAudit
     ├─ ICache::Put                      → LruCache::Put
     └─ IEventPublisher::Publish         → KafkaEventPublisher::Publish ("payments.submitted")
   (on any failure) PaymentHubService::Reject → Save, Put, Publish ("payments.rejected")
```

**Confirm settlement** (`"SETTLED"`)
```
PaymentHubService::ConfirmSettlement
 ├─ PaymentHubService::Load → ICache::Get → IPaymentRepository::FindById → ICache::Put
 ├─ IPaymentRepository::UpdateStatus → AppendAudit
 ├─ ICache::Put
 └─ IEventPublisher::Publish ("payments.settled")
```

**Routing rules** (`PaymentRouter::SelectRail`, limits from `rail_config`, bank participation from
`participant_banks`): non-INR → SWIFT; INR within RTGS limits (≥ ₹2 lakh) and bank on RTGS → RTGS;
urgent, within IMPS limits (≤ ₹5 lakh) and bank on IMPS → IMPS; bank on NEFT → NEFT; otherwise rejected.

## 7. Scanner traps included on purpose

- Interfaces are called through references, so the real target is only known from the wiring in `main.cpp`.
- `ICache` / `LruCache` are templates; `LruCache` has no `.cpp` file.
- Logging is done through macros that expand to `Logger::Instance().Log(...)`.
- `sqlite3` and `rd_kafka_s` are forward-declared in headers; the real includes are only in the `.cpp` files.
- `ws2_32.lib` is linked twice: by `#pragma comment(lib, ...)` in `WinsockSession.cpp` and in `App.vcxproj`.
- Java services appear only as host/port/URL strings and comments — there is no Java code in the repo.
- SQL scripts are loaded at runtime by scanning a directory (`MigrationRunner::FindScripts`); no C++ file names a specific `.sql` file. The link exists through the `migrationsDir` config value in `main.cpp` and the `<None>` items in `Storage.vcxproj`.
- `schema_version` is created by C++ code, not by any SQL script.
- The views are defined in SQL but never queried from C++.
- `db/queries/ops_reports.sql` is not referenced by the build or the code.
- SQL inside C++ is spread across string literals that the compiler concatenates.
- Configuration values (database path, Kafka brokers, rail connector host/port, bank BIC, Python script path, channel port, cache capacity) are loaded from `appsettings.json` at runtime via inline JSON parsing in `main.cpp`. The file is not referenced by any header; the dependency chain is invisible at compile time.
- Precompiled headers (`pch.h` / `pch.cpp`) in each module; scanner must track `<PrecompiledHeader>Use</PrecompiledHeader>` and `<PrecompiledHeader>Create</PrecompiledHeader>` in `.vcxproj` to understand what headers are transitively included.
- Include guard style is mixed: `WinsockSession.h`, `Pacs008Builder.h`, `WireProtocol.h` use `#ifndef`/`#define`/`#endif` guards; other headers use `#pragma once`. Both are equivalent but require scanner to handle both patterns.
- Binary message format uses `#pragma pack(1)` in `WireProtocol.h` with `static_assert` to verify struct sizes; pragma directives are not AST nodes and require special handling.
- Macros in `PaymentMacros.h` define `__declspec(dllexport)` / `__declspec(dllimport)` on Windows (preprocessor conditionals). Function-like macros `DECLARE_INTERFACE_METHOD()`, `IMPLEMENT_PROPERTY()`, etc. expand to multiple lines of code, obfuscating actual structure. `Logger` class applies `PAYHUB_EXPORT` which expands to `__declspec(dllexport)` only on Windows.
- Build property inheritance: `PayHub.Common.props` imports into all 6 `.vcxproj` files via `<Import Project="..\..\PayHub.Common.props" />`. Properties defined in `.props` are inherited by all projects; a scanner must resolve `.props` file imports and merge their definitions to understand the full configuration.
