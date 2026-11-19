package poc.cppscan.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poc.cppscan.extract.CallInfo;
import poc.cppscan.extract.Extractor;
import poc.cppscan.extract.FunctionInfo;
import poc.cppscan.msbuild.SourceFile;
import poc.cppscan.parse.ClangParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Resolving SQL and paths (steps 1–4), DATA_ACCESS one level, transactional, and the Winsock rule. */
class SqliteRuleTest {

    private static final String SOURCE = """
            #include <winsock2.h>
            #include <sqlite3.h>
            #include <string>

            #define SQL_DELETE "DELETE FROM payment " \\
                               "WHERE id = ?"
            constexpr const char* kSelectMerchant = "SELECT name FROM merchant WHERE id = ?";
            struct Queries { static constexpr const char* kCount = "SELECT COUNT(*) FROM payment"; };

            struct Store {
                sqlite3* db = nullptr;
                void open() { sqlite3_open("data/payments.db", &db); }
                void openAt(const std::string& path) { sqlite3_open(path.c_str(), &db); }
                void schema() {
                    sqlite3_exec(db, "CREATE TABLE IF NOT EXISTS merchant (id INTEGER PRIMARY KEY, name TEXT);"
                                     "CREATE TABLE payment (id INTEGER PRIMARY KEY,"
                                     " merchant_id INTEGER REFERENCES merchant(id));", nullptr, nullptr, nullptr);
                }
                bool insert(int merchant) {
                    const char* sql = "INSERT INTO payment (merchant_id) VALUES (?)";
                    sqlite3_stmt* stmt = nullptr;
                    sqlite3_exec(db, "BEGIN", nullptr, nullptr, nullptr);
                    sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr);
                    sqlite3_bind_int(stmt, 1, merchant);
                    bool ok = sqlite3_step(stmt) == SQLITE_DONE;
                    sqlite3_finalize(stmt);
                    sqlite3_exec(db, "COMMIT", nullptr, nullptr, nullptr);
                    return ok;
                }
                void find(const std::string& where) {
                    sqlite3_stmt* stmt = nullptr;
                    std::string sql = "SELECT * FROM payment WHERE " + where;
                    sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr);
                    std::string local = "SELECT id FROM merchant";
                    sqlite3_prepare_v2(db, local.c_str(), -1, &stmt, nullptr);
                    sqlite3_prepare_v2(db, kSelectMerchant, -1, &stmt, nullptr);
                    sqlite3_prepare_v2(db, Queries::kCount, -1, &stmt, nullptr);
                    sqlite3_prepare_v2(db, SQL_DELETE, -1, &stmt, nullptr);
                }
            };

            struct Service { Store store; bool pay() { return store.insert(7); } };

            void net(SOCKET s, const char* buf) {
                connect(s, nullptr, 0);
                send(s, buf, 1, 0);
                htons(80);
            }
            """;

    private static Extractor ex;

    @BeforeAll
    static void parse(@TempDir Path root) throws Exception {
        Path cpp = Files.createDirectories(root.resolve("src")).resolve("store.cpp");
        Files.writeString(cpp, SOURCE);
        Path stubInclude = Path.of("src", "test", "resources", "stubs", "sqlite", "include").toAbsolutePath();
        SourceFile sf = new SourceFile("src/store.cpp", "Lib", cpp.toString(),
                List.of("--driver-mode=cl", "-fms-compatibility", "-fms-extensions", "/std:c++17", "/EHsc",
                        "/DWIN32_LEAN_AND_MEAN", "/I" + stubInclude));
        ex = new Extractor(root.toRealPath(), List.of(sf), Map.of("Lib", "src"));
        ClangParser.Result r = new ClangParser().parseFile(sf.fullPath(), sf.clangArgs(), tu -> ex.visit(sf, tu));
        ex.finish();
        assertTrue(r.isClean(), () -> "parse errors: " + r.diagnostics);
    }

    private static FunctionInfo fn(String name) {
        return ex.functions().values().stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<CallInfo> calls(String fn, String target) {
        return fn(fn).calls.stream().filter(c -> c.targetMethod.equals(target)).toList();
    }

    @Test
    void dataSourcesLiteralAndDynamic() {
        List<SqliteRule.DataSourceFact> ds = ex.sqliteRule.dataSources;
        assertEquals(2, ds.size());
        assertEquals("sqlite:data/payments.db", ds.get(0).url());
        assertNull(ds.get(0).expression());
        assertEquals("sqlite:<dynamic>", ds.get(1).url());
        assertEquals("path.c_str()", ds.get(1).expression());
    }

    @Test
    void literalVariableConstantStaticMemberMacroAndDynamic() {
        List<CallInfo> prepares = calls("find", "sqlite3_prepare_v2");
        assertEquals("<dynamic>", prepares.get(0).sql, "SELECT built at runtime");
        assertEquals("sql.c_str()", prepares.get(0).expression);
        assertNull(prepares.get(0).operation);
        assertEquals("SELECT id FROM merchant", prepares.get(1).sql, "local std::string initialised from a literal");
        assertEquals("SELECT name FROM merchant WHERE id = ?", prepares.get(2).sql, "constexpr global");
        assertEquals("SELECT COUNT(*) FROM payment", prepares.get(3).sql, "static constexpr member");
        assertEquals("DELETE FROM payment WHERE id = ?", prepares.get(4).sql, "#define with adjacent literals");
        assertNull(prepares.get(4).expression, "expression only when the SQL is dynamic");
        assertTrue(ex.sqliteRule.statements.stream().anyMatch(s -> "SQL_DELETE".equals(s.expression())),
                "a macro use tokenizes to its name, not to half the file");
        assertEquals("DELETE", prepares.get(4).operation);
        assertEquals(List.of("payment"), prepares.get(4).tables);

        CallInfo insert = calls("insert", "sqlite3_prepare_v2").get(0);
        assertEquals("INSERT INTO payment (merchant_id) VALUES (?)", insert.sql, "const char* variable");
        assertEquals("SAVE", insert.operation);
        assertEquals("DATA_ACCESS", insert.kind);
        assertEquals("sqlite3", insert.targetClass);
        assertEquals(List.of(), insert.targetMethodIds);

        CallInfo step = calls("insert", "sqlite3_step").get(0);
        assertEquals("DATA_ACCESS", step.kind);
        assertNull(step.sql, "other SQLite calls have no sql");
    }

    @Test
    void transactionalAndOneLevelDataAccess() {
        assertTrue(fn("insert").transactional);
        assertFalse(fn("find").transactional);
        CallInfo viaProject = calls("pay", "insert").get(0);
        assertEquals("DATA_ACCESS", viaProject.kind);
        assertEquals("SAVE", viaProject.operation);
        assertEquals(List.of("payment"), viaProject.tables);
        assertEquals("Store", viaProject.targetClass);
    }

    @Test
    void embeddedCreateTableStatementsAreRecorded() {
        SqliteRule.SqlFact schema = ex.sqliteRule.statements.stream()
                .filter(s -> s.sql() != null && s.sql().startsWith("CREATE")).findFirst().orElseThrow();
        List<SqlText.TableDef> defs = SqlText.createTables(schema.sql());
        assertEquals(List.of("merchant", "payment"), defs.stream().map(SqlText.TableDef::name).toList());
        assertEquals("merchant.id", defs.get(1).columns().get(1).references());
    }

    @Test
    void winsockCallsAreIpcWithRoles() {
        assertEquals(List.of("CLIENT connect", "IO send"),
                ex.winsockRule.ipc.stream().map(i -> i.role() + " " + i.operation()).toList());
        CallInfo send = calls("net", "send").get(0);
        assertEquals("IPC", send.kind);
        assertEquals("winsock", send.targetClass);
        assertTrue(send.async);
        assertEquals("IPC", calls("net", "htons").get(0).kind, "every winsock2.h call is IPC, only some get an entry");
    }
}
