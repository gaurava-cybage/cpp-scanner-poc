package poc.cppscan.rules;

import org.bytedeco.llvm.clang.CXCursor;
import org.bytedeco.llvm.clang.CXTranslationUnit;
import poc.cppscan.extract.CallInfo;
import poc.cppscan.extract.FunctionInfo;
import poc.cppscan.extract.SourceText;
import poc.cppscan.extract.SourceText.Tok;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.bytedeco.llvm.global.clang.*;

/**
 * T7 SQLite rule (docs/output-format.md, "Database calls" and "Resolving SQL and paths"). Applied to every call
 * whose declaration is in a file ending with sqlite3.h. Strings are resolved, never invented: a value that is not
 * a literal, a literal-initialised local, a const global / static member, or a string macro is "&lt;dynamic&gt;".
 */
public class SqliteRule {

    public static final String DYNAMIC = "<dynamic>";

    private static final Set<String> OPEN = Set.of("sqlite3_open", "sqlite3_open_v2", "sqlite3_open16");
    private static final Set<String> SQL_IN_ARG_1 = Set.of("sqlite3_exec", "sqlite3_prepare", "sqlite3_prepare_v2",
            "sqlite3_prepare_v3", "sqlite3_prepare16", "sqlite3_prepare16_v2", "sqlite3_prepare16_v3");

    /** One sqlite3_open* call. {@code expression} is set when the path is dynamic. */
    public record DataSourceFact(String module, String url, String sourceFile, int line, String expression) {}

    /** One SQL argument of an exec/prepare call. {@code sql} is null when dynamic. */
    public record SqlFact(String module, String methodId, String function, String sourceFile, int line, String sql,
                          String expression) {}

    private record Resolved(String value, String expression) {}

    public final List<DataSourceFact> dataSources = new ArrayList<>();
    public final List<SqlFact> statements = new ArrayList<>();

    /** Macro name -> string value, for the translation unit being walked (built on first need). */
    private CXTranslationUnit macroTu;
    private Map<String, String> macros;
    private final Function<CXCursor, List<CXCursor>> children;
    private final Function<CXCursor, String> usr;

    public SqliteRule(Function<CXCursor, List<CXCursor>> children, Function<CXCursor, String> usr) {
        this.children = children;
        this.usr = usr;
    }

    public static boolean isAnchor(String declFileLower) {
        return declFileLower.endsWith("/sqlite3.h") || declFileLower.equals("sqlite3.h");
    }

    public void apply(CXTranslationUnit tu, CXCursor call, FunctionInfo fn, CallInfo ci) {
        ci.kind = "DATA_ACCESS";
        ci.targetClass = "sqlite3";
        String name = ci.targetMethod;
        if (OPEN.contains(name)) {
            Resolved r = argument(tu, call, 0, fn);
            if (r != null) {
                dataSources.add(new DataSourceFact(fn.module, "sqlite:" + (r.value != null ? r.value : DYNAMIC),
                        fn.filePath, ci.line, r.value != null ? null : r.expression));
            }
        } else if (SQL_IN_ARG_1.contains(name)) {
            Resolved r = argument(tu, call, 1, fn);
            if (r == null) {
                return;
            }
            if (r.value != null) {
                ci.sql = r.value;
                ci.operation = SqlText.operation(r.value);
                List<String> tables = SqlText.tables(r.value);
                ci.tables = tables.isEmpty() ? null : tables;
                if (SqlText.isTransactional(r.value)) {
                    fn.transactional = true;
                }
            } else {
                ci.sql = DYNAMIC;
                ci.expression = r.expression;
            }
            statements.add(new SqlFact(fn.module, fn.id(), name, fn.filePath, ci.line, r.value, r.expression));
        }
    }

    private Resolved argument(CXTranslationUnit tu, CXCursor call, int index, FunctionInfo fn) {
        if (clang_Cursor_getNumArguments(call) <= index) {
            return null;
        }
        return resolve(tu, clang_Cursor_getArgument(call, index), fn.usr);
    }

    /** Steps 1–4 of "Resolving SQL and paths". */
    private Resolved resolve(CXTranslationUnit tu, CXCursor arg, String functionUsr) {
        List<Tok> toks = SourceText.tokens(tu, arg);
        String expression = SourceText.text(toks);

        // 1. String literal(s).
        String literal = SourceText.stringValue(toks);
        if (literal != null) {
            return new Resolved(literal, expression);
        }

        // 2./3. A variable used as "x", "ns::Class::x", or either followed by ".c_str()" / ".data()".
        int nameEnd = toks.size();
        if (nameEnd >= 4 && toks.get(nameEnd - 4).text().equals(".")
                && (toks.get(nameEnd - 3).text().equals("c_str") || toks.get(nameEnd - 3).text().equals("data"))
                && toks.get(nameEnd - 2).text().equals("(") && toks.get(nameEnd - 1).text().equals(")")) {
            nameEnd -= 4;
        }
        boolean plainName = toks.size() == 1 && toks.get(0).kind() == CXToken_Identifier;
        if (isQualifiedName(toks, nameEnd)) {
            CXCursor var = referencedDecl(arg);
            if (var != null && clang_getCursorKind(var) == CXCursor_VarDecl) {
                CXCursor def = clang_getCursorDefinition(var);
                if (clang_Cursor_isNull(def) == 0) {
                    var = def;
                }
                boolean local = usr.apply(clang_getCursorSemanticParent(var)).equals(functionUsr);
                boolean constGlobal = !local && isConst(tu, var);
                if (local || constGlobal) {
                    String value = initializer(tu, var);
                    if (value != null) {
                        return new Resolved(value, expression);
                    }
                }
            } else if (var == null && plainName) {
                // 3. A #define that expands to a string literal.
                String value = macro(tu, toks.get(0).text());
                if (value != null) {
                    return new Resolved(value, expression);
                }
            }
        }
        // 4. Dynamic.
        return new Resolved(null, expression);
    }

    /** Tokens [0, end) are "name" or "A::B::name" (an optional leading "::" too). */
    private static boolean isQualifiedName(List<Tok> toks, int end) {
        if (end == 0) {
            return false;
        }
        int i = toks.get(0).text().equals("::") ? 1 : 0;
        boolean expectName = true;
        for (; i < end; i++) {
            Tok t = toks.get(i);
            if (expectName ? t.kind() != CXToken_Identifier : !t.text().equals("::")) {
                return false;
            }
            expectName = !expectName;
        }
        return !expectName; // ends with a name
    }

    /** The declaration referenced by the first DeclRefExpr inside the expression, or null. */
    private CXCursor referencedDecl(CXCursor expr) {
        if (clang_getCursorKind(expr) == CXCursor_DeclRefExpr) {
            CXCursor ref = clang_getCursorReferenced(expr);
            return clang_Cursor_isNull(ref) != 0 ? null : ref;
        }
        for (CXCursor child : children.apply(expr)) {
            CXCursor found = referencedDecl(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean isConst(CXTranslationUnit tu, CXCursor var) {
        if (clang_isConstQualifiedType(clang_getCursorType(var)) != 0) {
            return true;
        }
        return SourceText.tokens(tu, var).stream().anyMatch(t -> t.text().equals("constexpr"));
    }

    /** The literal a variable is initialised from: "x = "..."", "x("...")" or "x{"..."}"; else null. */
    private static String initializer(CXTranslationUnit tu, CXCursor var) {
        List<Tok> toks = SourceText.tokens(tu, var);
        String name = poc.cppscan.extract.Extractor.str(clang_getCursorSpelling(var));
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i).kind() == CXToken_Identifier && toks.get(i).text().equals(name)) {
                List<Tok> rest = toks.subList(i + 1, toks.size());
                if (!rest.isEmpty() && rest.get(0).text().equals("=")) {
                    rest = rest.subList(1, rest.size());
                }
                if (rest.isEmpty()) {
                    return null;
                }
                return SourceText.stringValue(rest);
            }
        }
        return null;
    }

    private String macro(CXTranslationUnit tu, String name) {
        if (macros == null || macroTu == null || !macroTu.equals(tu)) {
            macroTu = tu;
            macros = new HashMap<>();
            for (CXCursor c : children.apply(clang_getTranslationUnitCursor(tu))) {
                if (clang_getCursorKind(c) != CXCursor_MacroDefinition || clang_Cursor_isMacroFunctionLike(c) != 0) {
                    continue;
                }
                List<Tok> toks = SourceText.tokens(tu, c);
                if (toks.size() >= 2) {
                    String value = SourceText.stringValue(toks.subList(1, toks.size()));
                    if (value != null) {
                        macros.put(toks.get(0).text(), value);
                    }
                }
            }
        }
        return macros.get(name);
    }
}
