package poc.cppscan.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Simple keyword parsing of SQL text (docs/output-format.md: no SQL parser library). Case-insensitive; SQL comments
 * and string literals are ignored.
 */
public final class SqlText {

    private SqlText() {}

    public record Column(String name, String type, boolean primaryKey, boolean nullable, String references) {}

    public record Relationship(String type, String column, String references) {}

    /** One CREATE TABLE; {@code offset} is where it starts in the text (to compute a line). */
    public record TableDef(String name, List<Column> columns, List<Relationship> relationships, int offset) {}

    private static final Pattern TRANSACTION = Pattern.compile("\\b(BEGIN|COMMIT|ROLLBACK)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOT_A_TABLE = Set.of("SELECT", "SET", "WHERE", "VALUES", "DEFAULT", "LATERAL");

    // ---------------------------------------------------------------- tokens

    /** A word, a quoted identifier (unquoted), or a single punctuation character. String literals are skipped. */
    record Tok(String text, boolean word, int offset) {}

    static List<Tok> tokenize(String sql) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'') {
                int start = i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { i += 2; continue; }
                        i++;
                        break;
                    }
                    i++;
                }
                out.add(new Tok("'", false, start)); // a string value, content not needed
            } else if (c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int end = sql.indexOf(close, i + 1);
                if (end < 0) end = n;
                out.add(new Tok(sql.substring(i + 1, end), true, i));
                i = Math.min(n, end + 1);
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                out.add(new Tok(sql.substring(start, i), true, start));
            } else {
                out.add(new Tok(String.valueOf(c), false, i));
                i++;
            }
        }
        return out;
    }

    private static boolean is(Tok t, String keyword) {
        return t.word() && t.text().equalsIgnoreCase(keyword);
    }

    // ---------------------------------------------------------------- operation, tables, transactions

    /**
     * READ for SELECT; SAVE for INSERT/REPLACE/UPDATE; DELETE; SCHEMA for CREATE/ALTER/DROP. The first statement with
     * one of these wins ("BEGIN; INSERT ..." is SAVE). A WITH clause is skipped. Null when none is found.
     */
    public static String operation(String sql) {
        boolean statementStart = true;
        boolean inWith = false;
        int depth = 0;
        for (Tok t : tokenize(sql)) {
            if (t.text().equals("(")) depth++;
            else if (t.text().equals(")")) depth--;
            else if (t.text().equals(";")) { statementStart = true; inWith = false; depth = 0; continue; }
            if (!t.word()) continue;
            String w = t.text().toUpperCase(Locale.ROOT);
            if (statementStart) {
                statementStart = false;
                if (w.equals("WITH")) { inWith = true; continue; }
                String op = keywordOperation(w);
                if (op != null) return op;
                continue;
            }
            if (inWith && depth == 0) {
                String op = keywordOperation(w);
                if (op != null) return op;
            }
        }
        return null;
    }

    private static String keywordOperation(String w) {
        return switch (w) {
            case "SELECT" -> "READ";
            case "INSERT", "REPLACE", "UPDATE" -> "SAVE";
            case "DELETE" -> "DELETE";
            case "CREATE", "ALTER", "DROP" -> "SCHEMA";
            default -> null;
        };
    }

    /** Names after FROM, JOIN, INTO, UPDATE, DELETE FROM and CREATE TABLE; de-duplicated, in order. */
    public static List<String> tables(String sql) {
        List<Tok> toks = tokenize(sql);
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            int next = -1;
            if (is(t, "FROM") || is(t, "JOIN") || is(t, "INTO") || is(t, "UPDATE")) {
                next = i + 1;
                // "INSERT OR REPLACE INTO": the OR REPLACE part is before INTO, nothing to skip.
                if (is(t, "UPDATE") && next < toks.size() && is(toks.get(next), "OR")) next += 2; // UPDATE OR IGNORE t
            } else if (is(t, "TABLE") && i > 0) {
                next = skipIfNotExists(toks, i + 1);
            }
            if (next < 0 || next >= toks.size()) continue;
            String name = qualifiedName(toks, next);
            if (name != null && !NOT_A_TABLE.contains(name.toUpperCase(Locale.ROOT))) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    public static boolean isTransactional(String sql) {
        return TRANSACTION.matcher(stripComments(sql)).find();
    }

    static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }

    private static int skipIfNotExists(List<Tok> toks, int i) {
        if (i + 2 < toks.size() && is(toks.get(i), "IF") && is(toks.get(i + 1), "NOT") && is(toks.get(i + 2), "EXISTS")) {
            return i + 3;
        }
        return i;
    }

    /** "name" or "schema.name" at i; null if the token is not a name (e.g. "(" of a subquery). */
    private static String qualifiedName(List<Tok> toks, int i) {
        Tok t = toks.get(i);
        if (!t.word()) return null;
        if (i + 2 < toks.size() && toks.get(i + 1).text().equals(".") && toks.get(i + 2).word()) {
            return t.text() + "." + toks.get(i + 2).text();
        }
        return t.text();
    }

    // ---------------------------------------------------------------- CREATE TABLE

    private static final Set<String> COLUMN_CONSTRAINT_START = Set.of("CONSTRAINT", "PRIMARY", "NOT", "NULL", "UNIQUE",
            "CHECK", "DEFAULT", "COLLATE", "REFERENCES", "GENERATED", "AS");

    /** Every CREATE TABLE ... ( ... ) in the text. CREATE TABLE ... AS SELECT gives a table without columns. */
    public static List<TableDef> createTables(String sql) {
        List<Tok> toks = tokenize(sql);
        List<TableDef> result = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            if (!is(toks.get(i), "CREATE")) continue;
            int j = i + 1;
            while (j < toks.size() && (is(toks.get(j), "TEMP") || is(toks.get(j), "TEMPORARY"))) j++;
            if (j >= toks.size() || !is(toks.get(j), "TABLE")) continue;
            j = skipIfNotExists(toks, j + 1);
            if (j >= toks.size()) continue;
            String name = qualifiedName(toks, j);
            if (name == null) continue;
            j += name.contains(".") ? 3 : 1;
            List<Column> columns = new ArrayList<>();
            List<Relationship> relationships = new ArrayList<>();
            if (j < toks.size() && toks.get(j).text().equals("(")) {
                List<List<Tok>> defs = splitDefinitions(toks, j);
                parseDefinitions(defs, columns, relationships);
            }
            result.add(new TableDef(name, columns, relationships, toks.get(i).offset()));
        }
        return result;
    }

    /** The comma-separated parts inside the parentheses that start at {@code open}. */
    private static List<List<Tok>> splitDefinitions(List<Tok> toks, int open) {
        List<List<Tok>> defs = new ArrayList<>();
        List<Tok> current = new ArrayList<>();
        int depth = 0;
        for (int k = open; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.text().equals("(")) {
                if (depth++ == 0) continue;
            } else if (t.text().equals(")")) {
                if (--depth == 0) break;
            } else if (t.text().equals(",") && depth == 1) {
                defs.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(t);
        }
        if (!current.isEmpty()) defs.add(current);
        return defs;
    }

    private static void parseDefinitions(List<List<Tok>> defs, List<Column> columns, List<Relationship> relationships) {
        List<String> tablePk = new ArrayList<>();
        List<String[]> tableFks = new ArrayList<>(); // {column, references}
        for (List<Tok> def : defs) {
            if (def.isEmpty()) continue;
            int k = 0;
            if (is(def.get(0), "CONSTRAINT") && def.size() > 2) k = 2; // CONSTRAINT name ...
            Tok first = def.get(k);
            if (is(first, "PRIMARY")) {
                tablePk.addAll(parenWords(def, k));
            } else if (is(first, "FOREIGN")) {
                List<String> cols = parenWords(def, k);
                String ref = references(def, k);
                for (String c : cols) tableFks.add(new String[]{c, ref});
            } else if (is(first, "UNIQUE") || is(first, "CHECK")) {
                // table constraint without column facts
            } else if (first.word()) {
                columns.add(column(def));
            }
        }
        // Apply table-level PRIMARY KEY / FOREIGN KEY to the columns.
        for (int c = 0; c < columns.size(); c++) {
            Column col = columns.get(c);
            boolean pk = col.primaryKey() || tablePk.stream().anyMatch(p -> p.equalsIgnoreCase(col.name()));
            String ref = col.references();
            for (String[] fk : tableFks) {
                if (fk[0].equalsIgnoreCase(col.name()) && ref == null) ref = fk[1];
            }
            columns.set(c, new Column(col.name(), col.type(), pk, col.nullable() && !pk, ref));
        }
        for (Column col : columns) {
            if (col.references() != null) {
                relationships.add(new Relationship("FOREIGN_KEY", col.name(), col.references()));
            }
        }
    }

    private static Column column(List<Tok> def) {
        String name = def.get(0).text();
        int k = 1;
        StringBuilder type = new StringBuilder();
        while (k < def.size() && !(def.get(k).word() && COLUMN_CONSTRAINT_START.contains(def.get(k).text().toUpperCase(Locale.ROOT)))) {
            Tok t = def.get(k);
            String sofar = type.toString();
            if (!sofar.isEmpty() && t.word() && !sofar.endsWith("(") && !sofar.endsWith(" ")) type.append(' ');
            if (t.text().equals(",")) type.append(", ");
            else type.append(t.text());
            k++;
        }
        boolean pk = false, notNull = false;
        String ref = null;
        for (int m = k; m < def.size(); m++) {
            if (is(def.get(m), "PRIMARY")) pk = true;
            if (is(def.get(m), "NOT") && m + 1 < def.size() && is(def.get(m + 1), "NULL")) notNull = true;
            if (is(def.get(m), "REFERENCES")) ref = references(def, m - 1);
        }
        return new Column(name, type.toString().trim(), pk, !(pk || notNull), ref);
    }

    /** The words inside the first ( ) after position k. */
    private static List<String> parenWords(List<Tok> def, int k) {
        List<String> words = new ArrayList<>();
        boolean in = false;
        for (int m = k; m < def.size(); m++) {
            String t = def.get(m).text();
            if (t.equals("(")) { in = true; continue; }
            if (t.equals(")") && in) break;
            if (in && def.get(m).word()) words.add(t);
        }
        return words;
    }

    /** "REFERENCES t(c)" after position k -> "t.c" (or "t" without a column). */
    private static String references(List<Tok> def, int k) {
        for (int m = k + 1; m < def.size(); m++) {
            if (is(def.get(m), "REFERENCES") && m + 1 < def.size()) {
                String table = def.get(m + 1).text();
                List<String> cols = parenWords(def, m + 1);
                return cols.isEmpty() ? table : table + "." + cols.get(0);
            }
        }
        return null;
    }
}
