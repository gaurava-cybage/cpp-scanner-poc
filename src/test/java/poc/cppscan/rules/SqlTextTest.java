package poc.cppscan.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlTextTest {

    @Test
    void operations() {
        assertEquals("READ", SqlText.operation("select * from t"));
        assertEquals("SAVE", SqlText.operation("INSERT OR REPLACE INTO t VALUES (1)"));
        assertEquals("SAVE", SqlText.operation("REPLACE INTO t VALUES (1)"));
        assertEquals("SAVE", SqlText.operation("UPDATE t SET a = 1"));
        assertEquals("DELETE", SqlText.operation("DELETE FROM t WHERE id = ?"));
        assertEquals("SCHEMA", SqlText.operation("CREATE TABLE t (id INTEGER)"));
        assertEquals("SCHEMA", SqlText.operation("drop table t"));
        assertEquals("SAVE", SqlText.operation("BEGIN; INSERT INTO t VALUES (1); COMMIT;"), "first statement with an operation");
        assertEquals("READ", SqlText.operation("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertEquals("READ", SqlText.operation("-- comment with INSERT\nSELECT 1"));
        assertNull(SqlText.operation("BEGIN TRANSACTION;"));
        assertNull(SqlText.operation("PRAGMA journal_mode=WAL;"));
    }

    @Test
    void tables() {
        assertEquals(List.of("payments"), SqlText.tables("INSERT OR REPLACE INTO payments (a, b) VALUES (?, ?)"));
        assertEquals(List.of("payments", "payment_audit"),
                SqlText.tables("SELECT * FROM payments p JOIN payment_audit a ON a.id = p.id WHERE p.x IN (SELECT y FROM payments)"));
        assertEquals(List.of("t"), SqlText.tables("UPDATE t SET a = 1"));
        assertEquals(List.of("t"), SqlText.tables("DELETE FROM t"));
        assertEquals(List.of("schema_version"), SqlText.tables("CREATE TABLE IF NOT EXISTS schema_version (v INTEGER)"));
        assertEquals(List.of("main.t"), SqlText.tables("SELECT 1 FROM main.t"));
        assertEquals(List.of("t"), SqlText.tables("SELECT 'FROM x' FROM \"t\""), "string literals are not SQL");
        assertEquals(List.of(), SqlText.tables("SELECT 1"));
    }

    @Test
    void transactions() {
        assertTrue(SqlText.isTransactional("BEGIN TRANSACTION;"));
        assertTrue(SqlText.isTransactional("commit"));
        assertFalse(SqlText.isTransactional("SELECT began FROM t"));
        assertFalse(SqlText.isTransactional("-- BEGIN\nSELECT 1"));
    }

    @Test
    void createTableColumnsKeysAndReferences() {
        List<SqlText.TableDef> defs = SqlText.createTables("""
                -- comment
                CREATE TABLE IF NOT EXISTS merchant (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE payment (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    merchant_id INTEGER REFERENCES merchant(id),
                    amount      DECIMAL(10, 2) NOT NULL CHECK (amount > 0),
                    note        VARCHAR(20),
                    payer_id    INTEGER,
                    CONSTRAINT fk_payer FOREIGN KEY (payer_id) REFERENCES merchant (id)
                );
                CREATE INDEX ix ON payment (id);
                CREATE VIEW v AS SELECT 1;
                CREATE TABLE pair (a TEXT, b TEXT, PRIMARY KEY (a, b));
                """);
        assertEquals(List.of("merchant", "payment", "pair"), defs.stream().map(SqlText.TableDef::name).toList());

        SqlText.TableDef payment = defs.get(1);
        assertEquals(List.of(
                new SqlText.Column("id", "INTEGER", true, false, null),
                new SqlText.Column("merchant_id", "INTEGER", false, true, "merchant.id"),
                new SqlText.Column("amount", "DECIMAL(10, 2)", false, false, null),
                new SqlText.Column("note", "VARCHAR(20)", false, true, null),
                new SqlText.Column("payer_id", "INTEGER", false, true, "merchant.id")), payment.columns());
        assertEquals(List.of(
                new SqlText.Relationship("FOREIGN_KEY", "merchant_id", "merchant.id"),
                new SqlText.Relationship("FOREIGN_KEY", "payer_id", "merchant.id")), payment.relationships());

        assertTrue(defs.get(2).columns().stream().allMatch(c -> c.primaryKey() && !c.nullable()), "table-level PRIMARY KEY");
    }
}
