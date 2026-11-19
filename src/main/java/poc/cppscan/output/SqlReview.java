package poc.cppscan.output;

import poc.cppscan.rules.SqliteRule;

import java.util.Comparator;
import java.util.List;

/** out/sql-review.md: every SQL statement found in the code, resolved or dynamic, for the C++ developer to check. */
public class SqlReview {

    public static String markdown(String solutionName, SqliteRule rule) {
        List<SqliteRule.SqlFact> statements = rule.statements.stream()
                .sorted(Comparator.comparing(SqliteRule.SqlFact::sourceFile).thenComparingInt(SqliteRule.SqlFact::line))
                .toList();
        long dynamic = statements.stream().filter(s -> s.sql() == null).count();
        StringBuilder sb = new StringBuilder();
        sb.append("# SQL review: ").append(solutionName).append("\n\n");
        sb.append("Every SQL argument of an `sqlite3_exec` / `sqlite3_prepare*` call found by the scanner: ")
                .append(statements.size()).append(" statements, ").append(statements.size() - dynamic)
                .append(" resolved, ").append(dynamic).append(" dynamic.\n\n");
        sb.append("Please check each row: is the SQL right, and could a `<dynamic>` one have been resolved?\n\n");
        sb.append("| # | Location | Function | Call | Status | SQL (or the argument's source text when dynamic) | OK? |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int n = 1;
        for (SqliteRule.SqlFact s : statements) {
            boolean resolved = s.sql() != null;
            sb.append("| ").append(n++)
                    .append(" | `").append(s.sourceFile()).append(":").append(s.line()).append('`')
                    .append(" | `").append(cell(s.methodId())).append('`')
                    .append(" | `").append(s.function()).append('`')
                    .append(" | ").append(resolved ? "resolved" : "dynamic")
                    .append(" | `").append(cell(resolved ? s.sql() : s.expression())).append('`')
                    .append(" | [ ] |\n");
        }
        sb.append("\nData sources (`sqlite3_open*`):\n\n");
        for (SqliteRule.DataSourceFact d : rule.dataSources.stream()
                .sorted(Comparator.comparing(SqliteRule.DataSourceFact::sourceFile)
                        .thenComparingInt(SqliteRule.DataSourceFact::line)).toList()) {
            sb.append("- `").append(d.sourceFile()).append(":").append(d.line()).append("` `").append(d.url()).append('`');
            if (d.expression() != null) {
                sb.append(" (argument: `").append(cell(d.expression())).append("`)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** One table cell: whitespace collapsed, pipes and backticks escaped. */
    private static String cell(String s) {
        return s.replaceAll("\\s+", " ").replace("|", "\\|").replace("`", "'").trim();
    }
}
