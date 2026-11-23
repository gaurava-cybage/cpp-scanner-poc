package poc.cppscan.extract;

import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.clang.CXCursor;
import org.bytedeco.llvm.clang.CXFile;
import org.bytedeco.llvm.clang.CXSourceLocation;
import org.bytedeco.llvm.clang.CXSourceRange;
import org.bytedeco.llvm.clang.CXToken;
import org.bytedeco.llvm.clang.CXTranslationUnit;
import poc.cppscan.extract.CallInfo.ControlStep;
import poc.cppscan.extract.SourceText.Tok;
import poc.cppscan.rules.SqliteRule;
import poc.cppscan.rules.WinsockRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.bytedeco.llvm.global.clang.*;

/**
 * T5: walks one function body and fills calls (seq, controlPath, async) and exits. Calls are numbered post-order,
 * so in f(g()) g comes first. Calls inside lambdas stay in the enclosing function; returns inside lambdas are not
 * exits of it.
 */
class BodyWalker {

    private static final int MAX_CONDITION = 120;

    private final Extractor ex;
    private final FunctionInfo fn;
    private final CXTranslationUnit tu;
    private final List<ControlStep> path = new ArrayList<>();
    private int seq;

    BodyWalker(Extractor ex, FunctionInfo fn, CXCursor function) {
        this.ex = ex;
        this.fn = fn;
        this.tu = clang_Cursor_getTranslationUnit(function);
    }

    void walkFunction(CXCursor function) {
        for (CXCursor c : ex.children(function)) {
            if (clang_getCursorKind(c) != CXCursor_ParmDecl) { // default arguments belong to the caller
                walk(c, false);
            }
        }
    }

    private void walk(CXCursor c, boolean inLambda) {
        int k = clang_getCursorKind(c);
        switch (k) {
            case CXCursor_IfStmt -> walkIf(c, inLambda);
            case CXCursor_SwitchStmt -> walkSwitch(c, inLambda);
            case CXCursor_ForStmt -> walkLoop(c, "FOR", inLambda);
            case CXCursor_CXXForRangeStmt -> walkLoop(c, "RANGE_FOR", inLambda);
            case CXCursor_WhileStmt -> walkLoop(c, "WHILE", inLambda);
            case CXCursor_DoStmt -> walkDo(c, inLambda);
            case CXCursor_CXXTryStmt -> walkTry(c, inLambda);
            case CXCursor_LambdaExpr -> {
                for (CXCursor child : ex.children(c)) {
                    if (clang_getCursorKind(child) != CXCursor_ParmDecl) {
                        walk(child, true);
                    }
                }
            }
            case CXCursor_CallExpr -> {
                walkChildren(c, inLambda);
                recordCall(c);
            }
            case CXCursor_ReturnStmt -> {
                walkChildren(c, inLambda);
                if (!inLambda) fn.exits.add(new CallInfo.ExitInfo("RETURN", List.copyOf(path), line(c)));
            }
            case CXCursor_CXXThrowExpr -> {
                walkChildren(c, inLambda);
                if (!inLambda) fn.exits.add(new CallInfo.ExitInfo("THROW", List.copyOf(path), line(c)));
            }
            case CXCursor_ClassDecl, CXCursor_StructDecl, CXCursor_ClassTemplate -> { } // local classes: not this body
            default -> walkChildren(c, inLambda);
        }
    }

    private void walkChildren(CXCursor c, boolean inLambda) {
        for (CXCursor child : ex.children(c)) {
            walk(child, inLambda);
        }
    }

    // ------------------------------------------------------------ control statements

    private void walkIf(CXCursor c, boolean inLambda) {
        List<Tok> toks = tokens(c);
        int[] parens = parens(toks);
        String cond = parens == null ? "" : text(toks, parens[0] + 1, parens[1]);
        int closeOffset = parens == null ? -1 : toks.get(parens[1]).end();

        List<CXCursor> branches = new ArrayList<>();
        List<CXCursor> children = ex.children(c);
        if (closeOffset < 0) {
            // No tokens (e.g. the if comes from a macro): assume [cond, then, else].
            if (!children.isEmpty()) walk(children.get(0), inLambda);
            if (children.size() > 1) branches.addAll(children.subList(1, children.size()));
        } else {
            for (CXCursor child : children) {
                if (startOffset(child) >= closeOffset) {
                    branches.add(child);
                } else {
                    walk(child, inLambda); // init statement and condition: evaluated before branching
                }
            }
        }
        int line = line(c);
        if (!branches.isEmpty()) {
            inStep(new ControlStep("IF", cond, "then", line), branches.get(0), inLambda);
        }
        if (branches.size() > 1) {
            inStep(new ControlStep("IF", cond, "else", line), branches.get(1), inLambda);
        }
    }

    private void walkLoop(CXCursor c, String kind, boolean inLambda) {
        List<CXCursor> children = ex.children(c);
        if (children.isEmpty()) {
            return;
        }
        List<Tok> toks = tokens(c);
        int[] parens = parens(toks);
        String header = parens == null ? "" : text(toks, parens[0] + 1, parens[1]);
        for (int i = 0; i < children.size() - 1; i++) {
            walk(children.get(i), inLambda); // header parts
        }
        inStep(new ControlStep(kind, header, "body", line(c)), children.get(children.size() - 1), inLambda);
    }

    private void walkDo(CXCursor c, boolean inLambda) {
        List<CXCursor> children = ex.children(c);
        if (children.isEmpty()) {
            return;
        }
        String cond = children.size() > 1 ? textOf(children.get(1)) : "";
        inStep(new ControlStep("DO", cond, "body", line(c)), children.get(0), inLambda);
        for (int i = 1; i < children.size(); i++) {
            walk(children.get(i), inLambda);
        }
    }

    private void walkSwitch(CXCursor c, boolean inLambda) {
        List<CXCursor> children = ex.children(c);
        if (children.isEmpty()) {
            return;
        }
        List<Tok> toks = tokens(c);
        int[] parens = parens(toks);
        String cond = parens == null ? "" : text(toks, parens[0] + 1, parens[1]);
        int line = line(c);
        for (int i = 0; i < children.size() - 1; i++) {
            walk(children.get(i), inLambda);
        }
        CXCursor body = children.get(children.size() - 1);
        List<CXCursor> stmts = clang_getCursorKind(body) == CXCursor_CompoundStmt ? ex.children(body) : List.of(body);

        // A CaseStmt holds only the first statement after its label; later siblings keep the current label.
        String label = null;
        for (CXCursor stmt : stmts) {
            int k = clang_getCursorKind(stmt);
            CXCursor target = stmt;
            if (k == CXCursor_CaseStmt || k == CXCursor_DefaultStmt) {
                List<String> labels = new ArrayList<>();
                while (k == CXCursor_CaseStmt || k == CXCursor_DefaultStmt) {
                    List<CXCursor> parts = ex.children(target);
                    if (k == CXCursor_DefaultStmt) {
                        labels.add("default");
                    } else {
                        labels.add(parts.isEmpty() ? "" : textOf(parts.get(0)));
                    }
                    if (parts.isEmpty()) {
                        target = null;
                        break;
                    }
                    target = parts.get(parts.size() - 1);
                    k = clang_getCursorKind(target);
                }
                label = String.join(", ", labels);
            }
            if (target == null) {
                continue;
            }
            if (label == null) {
                walk(target, inLambda);
            } else {
                inStep(new ControlStep("SWITCH", cond, label, line), target, inLambda);
            }
        }
    }

    private void walkTry(CXCursor c, boolean inLambda) {
        int line = line(c);
        for (CXCursor child : ex.children(c)) {
            if (clang_getCursorKind(child) == CXCursor_CXXCatchStmt) {
                List<Tok> toks = tokens(child);
                int[] parens = parens(toks);
                String type = parens == null ? "" : text(toks, parens[0] + 1, parens[1]);
                List<CXCursor> parts = ex.children(child);
                if (!parts.isEmpty()) {
                    inStep(new ControlStep("CATCH", type, "catch", line(child)), parts.get(parts.size() - 1), inLambda);
                }
            } else {
                inStep(new ControlStep("TRY", "", "try", line), child, inLambda);
            }
        }
    }

    private void inStep(ControlStep step, CXCursor body, boolean inLambda) {
        path.add(step);
        walk(body, inLambda);
        path.remove(path.size() - 1);
    }

    // ------------------------------------------------------------ calls

    private void recordCall(CXCursor call) {
        CXCursor ref = clang_getCursorReferenced(call);
        CallInfo ci = new CallInfo();
        ci.line = line(call);

        if (clang_Cursor_isNull(ref) != 0 || clang_isInvalid(clang_getCursorKind(ref)) != 0) {
            String spelling = Extractor.str(clang_getCursorSpelling(call));
            if (spelling.isEmpty()) {
                return; // nothing to name (e.g. call through an expression)
            }
            ci.targetMethod = spelling;
            ci.resolution = "UNRESOLVED";
            finish(ci);
            return;
        }
        int refKind = clang_getCursorKind(ref); // before the template swap: a template constructor stays "Constructor"
        CXCursor tmpl = clang_getSpecializedCursorTemplate(ref);
        if (clang_Cursor_isNull(tmpl) == 0) {
            ref = tmpl;
        }
        if ((refKind == CXCursor_Constructor || refKind == CXCursor_CXXMethod) && isImplicit(ref)) {
            return; // compiler-generated constructor or assignment operator
        }

        String declFile = declFile(ref);
        String declLower = declFile.toLowerCase(Locale.ROOT).replace('\\', '/');
        boolean system = clang_Location_isInSystemHeader(clang_getCursorLocation(ref)) != 0;
        boolean sqlite = SqliteRule.isAnchor(declLower);
        boolean winsock = WinsockRule.isAnchor(declLower);
        boolean anchor = sqlite || winsock;
        String name = Extractor.functionName(ref);
        CXCursor parent = clang_getCursorSemanticParent(ref);
        if (Extractor.str(clang_getCursorSpelling(parent)).startsWith("(lambda")) {
            return; // calling a lambda: its body's calls are already part of the enclosing function
        }
        boolean isClassParent = Extractor.isClassKind(clang_getCursorKind(parent));
        String ownerQualified = isClassParent ? ex.qualifiedName(parent) : "";
        boolean async = isAsync(name, refKind, ownerQualified, declLower);
        if (system && !anchor && !async) {
            return;
        }

        ci.targetMethod = name;
        ci.targetClass = relativeTo(ownerQualified, fn.packageName);
        ci.targetUsr = Extractor.str(clang_getCursorUSR(ref));
        ci.declFile = declFile;
        ci.async = async;
        ci.external = system || ex.relativePath(declFile).isEmpty();
        if (ci.external) {
            ci.resolution = "EXACT";
        } else {
            ci.targetMethodIds.add(ex.methodIdOf(ref));
            boolean virtual = clang_CXXMethod_isVirtual(ref) != 0;
            boolean qualified = virtual && isQualifiedCall(call, name);
            if (qualified) {
                ex.qualifiedVirtualCalls++;
            }
            ci.resolution = virtual && !qualified ? "VIRTUAL" : "EXACT";
        }
        // Anchor rules (T7) come first: they apply wherever the header lives, even vendored inside the solution.
        if (sqlite) {
            ci.external = true;
            ci.targetMethodIds.clear();
            ci.resolution = "EXACT";
            ex.sqliteRule.apply(tu, call, fn, ci);
        } else if (winsock) {
            ci.external = true;
            ci.targetMethodIds.clear();
            ci.resolution = "EXACT";
            ex.winsockRule.apply(fn, ci);
        }
        finish(ci);
    }

    private void finish(CallInfo ci) {
        ci.seq = ++seq;
        ci.controlPath = List.copyOf(path);
        fn.calls.add(ci);
    }

    /** Thread starts, PostMessage/PostThreadMessage (A/W) and socket send/sendto, checked by declaring header. */
    static boolean isAsync(String name, int kind, String ownerQualified, String declLower) {
        if (kind == CXCursor_Constructor && ownerQualified.equals("std::thread")) {
            return true;
        }
        return switch (name) {
            case "CreateThread" -> declLower.endsWith("processthreadsapi.h") || declLower.endsWith("winbase.h");
            case "_beginthreadex", "_beginthread" -> declLower.endsWith("process.h");
            case "PostMessageA", "PostMessageW", "PostThreadMessageA", "PostThreadMessageW" ->
                    declLower.endsWith("winuser.h");
            case "send", "sendto" -> declLower.endsWith("winsock2.h") || declLower.endsWith("winsock.h");
            default -> false;
        };
    }

    /** "Base::foo()" or "p->Base::foo()": the token before the method name is "::". */
    private boolean isQualifiedCall(CXCursor call, String name) {
        List<Tok> toks = tokens(call);
        for (int i = 1; i < toks.size() - 1; i++) {
            if (toks.get(i).text().equals(name) && toks.get(i + 1).text().equals("(")) {
                return toks.get(i - 1).text().equals("::");
            }
        }
        return false;
    }

    /** Implicit special members (constructors, operator=) share the class's location. */
    private static boolean isImplicit(CXCursor ctor) {
        CXCursor cls = clang_getCursorSemanticParent(ctor);
        return offset(clang_getCursorLocation(ctor)) == offset(clang_getCursorLocation(cls))
                && line(ctor) == line(cls);
    }

    /** "tinypay::core::FeeCache" seen from namespace "tinypay::core" -> "FeeCache". */
    static String relativeTo(String qualified, String namespace) {
        if (!namespace.isEmpty() && qualified.startsWith(namespace + "::")) {
            return qualified.substring(namespace.length() + 2);
        }
        return qualified;
    }

    private static String declFile(CXCursor ref) {
        CXFile file = new CXFile();
        int[] l = new int[1], col = new int[1], off = new int[1];
        clang_getSpellingLocation(clang_getCursorLocation(ref), file, l, col, off);
        return file.isNull() ? "" : Extractor.str(clang_getFileName(file));
    }

    // ------------------------------------------------------------ tokens and locations

    private List<Tok> tokens(CXCursor c) {
        return SourceText.tokens(tu, c);
    }

    /** Index of the first "(" and its matching ")", or null. */
    private static int[] parens(List<Tok> toks) {
        int open = -1, depth = 0;
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i).text();
            if (t.equals("(")) {
                if (open < 0) open = i;
                depth++;
            } else if (t.equals(")") && open >= 0 && --depth == 0) {
                return new int[]{open, i};
            }
        }
        return null;
    }

    /** Tokens [from, to) with whitespace collapsed; cut at 120 characters. */
    private static String text(List<Tok> toks, int from, int to) {
        String s = SourceText.text(toks, from, to);
        return s.length() > MAX_CONDITION ? s.substring(0, MAX_CONDITION) : s;
    }

    private String textOf(CXCursor c) {
        List<Tok> toks = tokens(c);
        return text(toks, 0, toks.size());
    }

    private static int startOffset(CXCursor c) {
        return offset(clang_getRangeStart(clang_getCursorExtent(c)));
    }

    private static int offset(CXSourceLocation loc) {
        return SourceText.offset(loc);
    }

    static int line(CXCursor c) {
        CXFile f = new CXFile();
        int[] l = new int[1], col = new int[1], off = new int[1];
        clang_getExpansionLocation(clang_getCursorLocation(c), f, l, col, off);
        return l[0];
    }
}
