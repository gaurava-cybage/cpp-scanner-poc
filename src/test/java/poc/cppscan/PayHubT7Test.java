package poc.cppscan;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import poc.cppscan.model.Facts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T7 "done when": the sample solution's database section, DATA_ACCESS calls and Winsock entries match the expected
 * values (checked by hand against the PayHub source). Sample-specific values are allowed here, in src/test only.
 * Runs the real scan (msbuild + libclang, ~40 s); skipped when PayHub or MSBuild is not on this machine.
 * SQLITE_DIR is passed as an msbuild property pointing at the stub header in src/test/resources/stubs/sqlite.
 */
class PayHubT7Test {

    private static final Path SLN = Path.of("D:/SampleCppCode/PayHub/PayHub.sln");
    private static Facts facts;

    @BeforeAll
    static void scan() throws Exception {
        assumeTrue(Files.isRegularFile(SLN), "PayHub fixture not on this machine");
        try {
            poc.cppscan.msbuild.MSBuildRunner.findMSBuild();
        } catch (Exception e) {
            assumeTrue(false, "MSBuild not found: " + e.getMessage());
        }
        String stub = Path.of("src", "test", "resources", "stubs", "sqlite").toAbsolutePath().toString();
        facts = Main.scan(new Main.Options(SLN, "Release", "x64", List.of("SQLITE_DIR=" + stub), false, false,
                List.of())).facts();
    }

    @Test
    void dataSources() {
        assertEquals(List.of(
                new Facts.DataSource("Storage", "sqlite:<dynamic>", "", "src/Storage/MigrationRunner.cpp", 22, "sqlite3", "dbPath_.c_str()"),
                new Facts.DataSource("Storage", "sqlite:<dynamic>", "", "src/Storage/SqlitePaymentRepository.cpp", 30, "sqlite3", "dbPath.c_str()"),
                new Facts.DataSource("Storage", "sqlite:<dynamic>", "", "src/Storage/SqliteRailConfigRepository.cpp", 28, "sqlite3", "dbPath_.c_str()")),
                facts.database().dataSources());
    }

    @Test
    void tablesAndMigrations() {
        List<String> tables = facts.database().tables().stream()
                .map(t -> t.source() + " " + t.tableName() + " " + t.module() + " " + t.sourceFile() + ":" + t.line()
                        + " pk=" + t.columns().stream().filter(Facts.Column::primaryKey).map(Facts.Column::name).toList()
                        + " cols=" + t.columns().size()
                        + " fk=" + t.relationships().stream().map(r -> r.column() + "->" + r.references()).toList())
                .toList();
        assertEquals(List.of(
                "EMBEDDED_SQL schema_version Storage src/Storage/MigrationRunner.cpp:87 pk=[version] cols=3 fk=[]",
                "SQL payments  db/migrations/V001__create_payment_tables.sql:4 pk=[end_to_end_id] cols=13 fk=[]",
                "SQL payment_audit  db/migrations/V001__create_payment_tables.sql:25 pk=[id] cols=4 fk=[end_to_end_id->payments.end_to_end_id]",
                "SQL rail_config  db/migrations/V002__create_reference_data_tables.sql:5 pk=[rail] cols=5 fk=[]",
                "SQL participant_banks  db/migrations/V002__create_reference_data_tables.sql:14 pk=[ifsc_prefix] cols=5 fk=[]"),
                tables);

        Facts.Table payments = facts.database().tables().get(1);
        assertEquals(new Facts.Column("rail_reference", "TEXT", false, true, null), payments.columns().get(9));
        assertEquals(new Facts.Column("amount_minor", "INTEGER", false, false, null), payments.columns().get(4));

        assertEquals("SQL_SCRIPTS", facts.database().migrationTool());
        assertEquals(List.of(
                "db/migrations/V001__create_payment_tables.sql",
                "db/migrations/V002__create_reference_data_tables.sql",
                "db/migrations/V003__create_reporting_views.sql",
                "db/migrations/V004__seed_reference_data.sql",
                "db/queries/ops_reports.sql"), facts.database().migrationScripts());
        assertEquals(5, facts.summary().tableCount());
        assertEquals(3, facts.summary().dataSourceCount());
    }

    /** Every exec/prepare call: file:line, function, operation, tables, then the SQL or "<dynamic> expression". */
    @Test
    void sqliteCallsWithSql() {
        List<String> found = new ArrayList<>();
        for (Facts.Method m : facts.codeFlow().methods()) {
            for (Facts.Call c : m.calls()) {
                if (c.sql() != null) {
                    assertEquals("DATA_ACCESS", c.kind());
                    assertEquals("sqlite3", c.targetClass());
                    found.add(m.sourceFile() + ":" + c.line() + " " + c.targetMethod() + " " + c.operation() + " "
                            + c.tables() + " " + c.table() + " | "
                            + (c.sql().equals("<dynamic>") ? "<dynamic> " + c.expression() : c.sql()));
                }
            }
        }
        found.sort(null);
        assertEquals(List.of(
                "src/Storage/MigrationRunner.cpp:111 sqlite3_exec null null null | BEGIN TRANSACTION;",
                "src/Storage/MigrationRunner.cpp:112 sqlite3_exec null null null | <dynamic> sql.c_str()",
                "src/Storage/MigrationRunner.cpp:115 sqlite3_exec null null null | ROLLBACK;",
                "src/Storage/MigrationRunner.cpp:120 sqlite3_prepare_v2 SAVE [schema_version] schema_version | INSERT INTO schema_version (version, script) VALUES (?, ?);",
                "src/Storage/MigrationRunner.cpp:127 sqlite3_exec null null null | <dynamic> ok ? \"COMMIT;\" : \"ROLLBACK;\"",
                "src/Storage/MigrationRunner.cpp:87 sqlite3_exec SCHEMA [schema_version] schema_version | CREATE TABLE IF NOT EXISTS schema_version ( version    INTEGER PRIMARY KEY, script     TEXT NOT NULL, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);",
                "src/Storage/MigrationRunner.cpp:97 sqlite3_prepare_v2 READ [schema_version] schema_version | SELECT COALESCE(MAX(version), 0) FROM schema_version;",
                "src/Storage/SqlitePaymentRepository.cpp:102 sqlite3_prepare_v2 SAVE [payments] payments | UPDATE payments SET status = ?, rail_reference = ?, updated_at = CURRENT_TIMESTAMP WHERE end_to_end_id = ?;",
                "src/Storage/SqlitePaymentRepository.cpp:119 sqlite3_prepare_v2 READ [payments] payments | SELECT debtor_account, creditor_account, creditor_agent, amount_minor, currency, priority, rail, status, rail_reference, screening_score FROM payments WHERE end_to_end_id = ?;",
                "src/Storage/SqlitePaymentRepository.cpp:45 sqlite3_exec null null null | <dynamic> sql",
                "src/Storage/SqlitePaymentRepository.cpp:55 sqlite3_prepare_v2 SAVE [payment_audit] payment_audit | INSERT INTO payment_audit (end_to_end_id, status) VALUES (?, ?);",
                "src/Storage/SqlitePaymentRepository.cpp:70 sqlite3_prepare_v2 SAVE [payments] payments | INSERT OR REPLACE INTO payments (end_to_end_id, debtor_account, creditor_account, creditor_agent, amount_minor, currency, priority, rail, status, rail_reference, screening_score) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);",
                "src/Storage/SqliteRailConfigRepository.cpp:35 sqlite3_prepare_v2 READ [rail_config] rail_config | SELECT rail, min_amount_minor, max_amount_minor, is_active FROM rail_config;",
                "src/Storage/SqliteRailConfigRepository.cpp:51 sqlite3_prepare_v2 READ [participant_banks] participant_banks | SELECT ifsc_prefix, supports_imps, supports_neft, supports_rtgs FROM participant_banks;"),
                found);
    }

    /** Calls to project functions that directly run a SQLite statement (one level). */
    @Test
    void projectCallsMarkedDataAccess() {
        List<String> found = new ArrayList<>();
        for (Facts.Method m : facts.codeFlow().methods()) {
            for (Facts.Call c : m.calls()) {
                if ("DATA_ACCESS".equals(c.kind()) && !"sqlite3".equals(c.targetClass())) {
                    found.add(m.id() + " L" + c.line() + " -> " + c.targetClass() + "::" + c.targetMethod() + " "
                            + c.operation() + " " + c.tables());
                }
            }
        }
        found.sort(null);
        assertEquals(List.of(
                "payhub::core::PaymentHubService#ConfirmSettlement(const std::string&, const std::string&) L87 -> IPaymentRepository::UpdateStatus SAVE [payments]",
                "payhub::core::PaymentHubService#Load(const std::string&) L102 -> IPaymentRepository::FindById READ [payments]",
                "payhub::core::PaymentHubService#Process(Payment) L61 -> IPaymentRepository::Save SAVE [payments]",
                "payhub::core::PaymentHubService#Reject(Payment, const std::string&) L122 -> IPaymentRepository::Save SAVE [payments]",
                "payhub::storage::MigrationRunner#Run() L28 -> MigrationRunner::EnsureVersionTable SCHEMA [schema_version]",
                "payhub::storage::MigrationRunner#Run() L31 -> MigrationRunner::CurrentVersion READ [schema_version]",
                "payhub::storage::MigrationRunner#Run() L38 -> MigrationRunner::Apply SAVE [schema_version]",
                "payhub::storage::SqlitePaymentRepository#Save(const core::Payment&) L89 -> SqlitePaymentRepository::AppendAudit SAVE [payment_audit]",
                "payhub::storage::SqlitePaymentRepository#UpdateStatus(const std::string&, core::PaymentStatus, const std::string&) L111 -> SqlitePaymentRepository::AppendAudit SAVE [payment_audit]",
                "payhub::storage::SqliteRailConfigRepository#SqliteRailConfigRepository(std::string) L23 -> SqliteRailConfigRepository::Reload READ [rail_config, participant_banks]"),
                found);

        List<String> transactional = facts.codeFlow().methods().stream()
                .filter(m -> Boolean.TRUE.equals(m.transactional())).map(Facts.Method::id).toList();
        assertEquals(List.of("payhub::storage::MigrationRunner#Apply(sqlite3*, int, const std::filesystem::path&)"),
                transactional);
    }

    @Test
    void winsockIpcEntries() {
        List<String> ipc = facts.integrations().ipc().stream()
                .map(i -> i.type() + " " + i.role() + " " + i.operation() + " " + i.sourceFile() + ":" + i.line()
                        + " " + i.confidence()).toList();
        assertEquals(List.of(
                "TCP_SOCKET SETUP socket src/Network/HttpRailConnector.cpp:55 CONFIRMED",
                "TCP_SOCKET CLIENT connect src/Network/HttpRailConnector.cpp:57 CONFIRMED",
                "TCP_SOCKET SETUP closesocket src/Network/HttpRailConnector.cpp:59 CONFIRMED",
                "TCP_SOCKET IO send src/Network/HttpRailConnector.cpp:70 CONFIRMED",
                "TCP_SOCKET IO recv src/Network/HttpRailConnector.cpp:75 CONFIRMED",
                "TCP_SOCKET SETUP closesocket src/Network/HttpRailConnector.cpp:78 CONFIRMED",
                "TCP_SOCKET SETUP socket src/Network/TcpHubServer.cpp:16 CONFIRMED",
                "TCP_SOCKET SERVER bind src/Network/TcpHubServer.cpp:27 CONFIRMED",
                "TCP_SOCKET SERVER listen src/Network/TcpHubServer.cpp:28 CONFIRMED",
                "TCP_SOCKET SETUP closesocket src/Network/TcpHubServer.cpp:30 CONFIRMED",
                "TCP_SOCKET SERVER accept src/Network/TcpHubServer.cpp:38 CONFIRMED",
                "TCP_SOCKET SETUP closesocket src/Network/TcpHubServer.cpp:44 CONFIRMED",
                "TCP_SOCKET IO recv src/Network/TcpHubServer.cpp:57 CONFIRMED",
                "TCP_SOCKET IO send src/Network/TcpHubServer.cpp:62 CONFIRMED",
                "TCP_SOCKET SETUP closesocket src/Network/TcpHubServer.cpp:66 CONFIRMED",
                "TCP_SOCKET SETUP WSAStartup src/Network/WinsockSession.cpp:13 CONFIRMED"), ipc);
        assertEquals("payhub::network::TcpHubServer#Run()", facts.integrations().ipc().get(7).methodId());
    }
}
