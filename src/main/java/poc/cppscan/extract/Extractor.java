package poc.cppscan.extract;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.clang.CXClientData;
import org.bytedeco.llvm.clang.CXCursor;
import org.bytedeco.llvm.clang.CXCursorVisitor;
import org.bytedeco.llvm.clang.CXFile;
import org.bytedeco.llvm.clang.CXSourceLocation;
import org.bytedeco.llvm.clang.CXString;
import org.bytedeco.llvm.clang.CXTranslationUnit;
import org.bytedeco.llvm.clang.CXType;
import poc.cppscan.msbuild.SourceFile;
import poc.cppscan.rules.SqliteRule;
import poc.cppscan.rules.WinsockRule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.bytedeco.llvm.global.clang.*;

/**
 * T4: walks each translation unit and collects class and function definitions declared in files under the solution
 * folder (system headers and third-party headers outside the solution are skipped). De-duplicated by USR.
 */
public class Extractor {

    private final Path solutionDir;
    private final String solutionPrefix;
    /** Lower-case solution-relative path of each ClCompile file -> its project. */
    private final Map<String, String> moduleByFile = new HashMap<>();
    /** Project name -> lower-case solution-relative folder, longest folder first. */
    private final Map<String, String> projectFolders = new LinkedHashMap<>();

    private final Map<String, ClassInfo> classes = new TreeMap<>();     // by USR
    private final Map<String, FunctionInfo> functions = new TreeMap<>(); // by USR
    /** Absolute file name from libclang -> solution-relative path (disk casing), or "" when outside the solution. */
    private final Map<String, String> relPathCache = new HashMap<>();

    private String currentModule = "";
    private int unresolvedFieldTypes;
    /** Calls like Base::foo() to a virtual method (resolved EXACT, not VIRTUAL). */
    int qualifiedVirtualCalls;
    private final List<EntryPoint> entryPoints = new ArrayList<>();

    /** T7 anchor rules; they collect data sources, SQL statements and IPC entries while bodies are walked. */
    public final SqliteRule sqliteRule = new SqliteRule(this::children, c -> str(clang_getCursorUSR(c)));
    public final WinsockRule winsockRule = new WinsockRule();

    /** codeFlow.entryPoints[] */
    public record EntryPoint(String id, String kind, String module, String className, String methodId) {}

    // One visitor for the whole run (each JavaCPP callback allocates a native thunk).
    private List<CXCursor> sink;
    private final CXCursorVisitor collector = new CXCursorVisitor() {
        @Override
        public int call(CXCursor cursor, CXCursor parent, CXClientData data) {
            CXCursor copy = new CXCursor();
            copy.put(cursor);
            sink.add(copy);
            return CXChildVisit_Continue;
        }
    };

    /**
     * @param projectFolders project name -> solution-relative folder of its .vcxproj (forward slashes), in .sln order
     */
    public Extractor(Path solutionDir, Collection<SourceFile> files, Map<String, String> projectFolders) {
        this.solutionDir = solutionDir;
        this.solutionPrefix = solutionDir.toString().toLowerCase(Locale.ROOT) + "\\";
        files.forEach(f -> moduleByFile.putIfAbsent(f.relPath(), f.module()));
        projectFolders.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getValue().length()).reversed())
                .forEach(e -> this.projectFolders.put(e.getKey(), e.getValue().toLowerCase(Locale.ROOT)));
    }

    public Map<String, ClassInfo> classes() {
        return classes;
    }

    public Map<String, FunctionInfo> functions() {
        return functions;
    }

    public int unresolvedFieldTypes() {
        return unresolvedFieldTypes;
    }

    public void visit(SourceFile file, CXTranslationUnit tu) {
        currentModule = file.module();
        walk(clang_getTranslationUnitCursor(tu));
    }

    private record DbAccess(String operation, List<String> tables) {}

    /**
     * T7, one level: a call to a project function whose own body has a SQLite call with a known operation becomes
     * DATA_ACCESS with that operation (the first one in seq order) and the tables of all its SQLite calls.
     * For a VIRTUAL call the implementations are checked too.
     */
    private void markDataAccessCalls() {
        Map<String, DbAccess> direct = new HashMap<>();
        for (FunctionInfo f : functions.values()) {
            String op = null;
            java.util.LinkedHashSet<String> tables = new java.util.LinkedHashSet<>();
            for (CallInfo c : f.calls) {
                if ("sqlite3".equals(c.targetClass) && c.operation != null) {
                    if (op == null) op = c.operation;
                    if (c.tables != null) tables.addAll(c.tables);
                }
            }
            if (op != null) {
                direct.putIfAbsent(f.id(), new DbAccess(op, List.copyOf(tables)));
            }
        }
        for (FunctionInfo f : functions.values()) {
            for (CallInfo c : f.calls) {
                if (!"CALL".equals(c.kind)) continue;
                for (String id : c.targetMethodIds) {
                    DbAccess db = direct.get(id);
                    if (db != null) {
                        c.kind = "DATA_ACCESS";
                        c.operation = db.operation();
                        c.tables = db.tables().isEmpty() ? null : db.tables();
                        break;
                    }
                }
            }
        }
    }

    public List<EntryPoint> entryPoints() {
        return entryPoints;
    }

    public int qualifiedVirtualCalls() {
        return qualifiedVirtualCalls;
    }

    /**
     * Call once after all files: fills sourceFiles, adds the overriding implementations to VIRTUAL calls, and
     * collects the entry points.
     */
    public void finish() {
        for (FunctionInfo f : functions.values()) {
            ClassInfo owner = classes.get(f.classUsr);
            if (owner != null && isSourceFile(f.filePath)) {
                owner.sourceFiles.add(f.filePath);
            }
        }

        // usr -> ids of every function that overrides it, directly or through a chain.
        Map<String, List<String>> directOverriders = new HashMap<>();
        for (FunctionInfo f : functions.values()) {
            f.overriddenUsrs.forEach(u -> directOverriders.computeIfAbsent(u, k -> new ArrayList<>()).add(f.usr));
        }
        for (FunctionInfo f : functions.values()) {
            for (CallInfo call : f.calls) {
                if (!"VIRTUAL".equals(call.resolution)) {
                    continue;
                }
                java.util.TreeSet<String> impls = new java.util.TreeSet<>();
                java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(call.targetUsr));
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (!todo.isEmpty()) {
                    String u = todo.pop();
                    if (!seen.add(u)) continue;
                    for (String o : directOverriders.getOrDefault(u, List.of())) {
                        impls.add(functions.get(o).id());
                        todo.push(o);
                    }
                }
                impls.remove(call.targetMethodIds.get(0));
                call.targetMethodIds.addAll(impls); // declared target first, then implementations sorted
            }
        }

        markDataAccessCalls();

        for (FunctionInfo f : functions.values()) {
            String kind = null;
            if (f.className.isEmpty() && f.packageName.isEmpty()) {
                kind = switch (f.name) {
                    case "main", "wmain" -> "MAIN";
                    case "WinMain", "wWinMain" -> "WINMAIN";
                    case "DllMain" -> "DLL_MAIN";
                    default -> null;
                };
            }
            // Members of a dllexport class inherit the attribute; only functions marked themselves are entry points.
            ClassInfo owner = classes.get(f.classUsr);
            boolean inherited = owner != null && owner.annotations.contains("dllexport");
            if (kind == null && f.dllExport && !inherited) {
                kind = "DLL_EXPORT";
            }
            if (kind != null) {
                String id = kind.equals("DLL_EXPORT") ? "DLL_EXPORT " + f.module + "!" + f.name : kind + " " + f.module;
                entryPoints.add(new EntryPoint(id, kind, f.module, f.className, f.id()));
            }
        }
        entryPoints.sort(Comparator.comparing(EntryPoint::id));
    }

    // ---------------------------------------------------------------- walking

    private void walk(CXCursor parent) {
        for (CXCursor c : children(parent)) {
            int kind = clang_getCursorKind(c);
            if (!isInSolution(c)) {
                continue;
            }
            if (kind == CXCursor_Namespace || kind == CXCursor_LinkageSpec || kind == CXCursor_UnexposedDecl) {
                walk(c);
            } else if (isClassKind(kind)) {
                if (clang_isCursorDefinition(c) != 0) {
                    recordClass(c, kind);
                    walk(c); // nested classes and in-class method definitions
                }
            } else if (isFunctionKind(kind)) {
                if (clang_isCursorDefinition(c) != 0) {
                    recordFunction(c, kind);
                }
            }
        }
    }

    private void recordClass(CXCursor c, int kind) {
        String usr = str(clang_getCursorUSR(c));
        String name = str(clang_getCursorSpelling(c));
        if (usr.isEmpty() || classes.containsKey(usr) || name.isEmpty() || clang_Cursor_isAnonymous(c) != 0) {
            return;
        }
        String[] loc = location(c);
        Scope scope = scope(c);

        ClassInfo ci = new ClassInfo();
        ci.usr = usr;
        ci.className = scope.outerClasses.isEmpty() ? name : scope.outerClasses + "::" + name;
        ci.packageName = scope.namespace;
        ci.filePath = loc[0];
        ci.line = Integer.parseInt(loc[1]);

        boolean isStruct = kind == CXCursor_StructDecl
                || (kind == CXCursor_ClassTemplate && clang_getTemplateCursorKind(c) == CXCursor_StructDecl);
        boolean isFinal = false, dllexport = false, hasCtorOrDtor = false;
        int fieldCount = 0, methodCount = 0, pureCount = 0;

        for (CXCursor m : children(c)) {
            int mk = clang_getCursorKind(m);
            if (mk == CXCursor_CXXFinalAttr) {
                isFinal = true;
            } else if (mk == CXCursor_DLLExport) {
                dllexport = true;
            } else if (mk == CXCursor_CXXBaseSpecifier) {
                ci.baseClasses.add(new ClassInfo.BaseClass(
                        cleanType(str(clang_getTypeSpelling(clang_getCursorType(m)))),
                        access(clang_getCXXAccessSpecifier(m)), clang_isVirtualBase(m) != 0));
            } else if (mk == CXCursor_FieldDecl) {
                fieldCount++;
                CXType t = clang_getCursorType(m);
                ci.fields.add(str(clang_getCursorSpelling(m)) + " : " + str(clang_getTypeSpelling(t)));
                if (clang_isInvalidDeclaration(m) != 0) {
                    unresolvedFieldTypes++;
                    ci.fields.set(ci.fields.size() - 1, ci.fields.get(ci.fields.size() - 1) + "  (unresolved type)");
                } else {
                    String dep = dependencyType(t);
                    if (dep != null) {
                        ci.dependencyTypes.add(dep);
                    }
                }
            } else if (mk == CXCursor_CXXMethod || mk == CXCursor_FunctionTemplate || mk == CXCursor_ConversionFunction) {
                methodCount++;
                boolean pure = clang_CXXMethod_isPureVirtual(m) != 0;
                if (pure) {
                    pureCount++;
                }
                ci.methods.add(describeMethod(m));
            } else if (mk == CXCursor_Constructor || mk == CXCursor_Destructor) {
                hasCtorOrDtor = true;
                ci.methods.add(describeMethod(m));
            }
        }

        if (isFinal) ci.annotations.add("final");
        if (clang_CXXRecord_isAbstract(c) != 0) ci.annotations.add("abstract");
        if (isStruct) ci.annotations.add("struct");
        if (dllexport) ci.annotations.add("dllexport");

        if (dllexport) {
            ci.type = "EXPORTED_API";
        } else if (methodCount > 0 && pureCount == methodCount && fieldCount == 0) {
            ci.type = "INTERFACE";
        } else if (isStruct && fieldCount > 0 && methodCount == 0 && !hasCtorOrDtor) {
            ci.type = "ENTITY";
        } else {
            ci.type = "COMPONENT";
        }

        ci.module = moduleFor(ci.filePath);
        classes.put(usr, ci);
    }

    private void recordFunction(CXCursor c, int kind) {
        String usr = str(clang_getCursorUSR(c));
        if (usr.isEmpty() || functions.containsKey(usr)) {
            return;
        }
        String[] loc = location(c);
        Scope scope = scope(c);

        FunctionInfo fi = new FunctionInfo();
        fi.usr = usr;
        fi.name = functionName(c);
        fi.packageName = scope.namespace;
        CXCursor parent = clang_getCursorSemanticParent(c);
        if (isClassKind(clang_getCursorKind(parent))) {
            fi.classUsr = str(clang_getCursorUSR(parent));
            fi.className = qualifiedName(parent);
        } else {
            fi.classUsr = "";
            fi.className = "";
        }
        // Parameter types from the first declaration, so T5 builds the same ID from any translation unit.
        fi.paramTypes = paramTypes(clang_getCanonicalCursor(c));
        fi.returnType = (kind == CXCursor_Constructor || kind == CXCursor_Destructor)
                ? "" : str(clang_getTypeSpelling(clang_getCursorResultType(c)));
        fi.isVirtual = clang_CXXMethod_isVirtual(c) != 0;
        fi.isPureVirtual = clang_CXXMethod_isPureVirtual(c) != 0;
        fi.isStatic = clang_CXXMethod_isStatic(c) != 0;
        fi.isConst = clang_CXXMethod_isConst(c) != 0;
        fi.filePath = loc[0];
        fi.line = Integer.parseInt(loc[1]);
        fi.endLine = endLine(c);
        fi.module = moduleFor(fi.filePath);

        fi.doc = str(clang_Cursor_getBriefCommentText(c));
        if (fi.doc.isEmpty()) {
            fi.doc = str(clang_Cursor_getBriefCommentText(clang_getCanonicalCursor(c)));
        }
        fi.dllExport = hasChild(c, CXCursor_DLLExport) || hasChild(clang_getCanonicalCursor(c), CXCursor_DLLExport);
        fi.overriddenUsrs = overridden(c);
        functions.put(usr, fi);
        new BodyWalker(this, fi, c).walkFunction(c);
    }

    private static int endLine(CXCursor c) {
        CXFile f = new CXFile();
        int[] l = new int[1], col = new int[1], off = new int[1];
        clang_getExpansionLocation(clang_getRangeEnd(clang_getCursorExtent(c)), f, l, col, off);
        return l[0];
    }

    private boolean hasChild(CXCursor c, int kind) {
        for (CXCursor child : children(c)) {
            if (clang_getCursorKind(child) == kind) {
                return true;
            }
        }
        return false;
    }

    private List<String> overridden(CXCursor c) {
        List<String> result = new ArrayList<>();
        PointerPointer<CXCursor> out = new PointerPointer<>(1);
        IntPointer count = new IntPointer(1);
        clang_getOverriddenCursors(c, out, count);
        int n = count.get();
        if (n > 0 && out.get(0) != null) {
            CXCursor base = new CXCursor(out.get(0));
            for (int i = 0; i < n; i++) {
                result.add(str(clang_getCursorUSR(base.getPointer(i))));
            }
            clang_disposeOverriddenCursors(base);
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    List<CXCursor> children(CXCursor parent) {
        List<CXCursor> previous = sink;
        List<CXCursor> result = new ArrayList<>();
        sink = result;
        clang_visitChildren(parent, collector, null);
        sink = previous;
        return result;
    }

    /** Spelling, except that constructors/destructors of class templates drop "<K, V>" ("LruCache<K, V>" -> "LruCache"). */
    static String functionName(CXCursor c) {
        String name = str(clang_getCursorSpelling(c));
        int k = clang_getCursorKind(c);
        if ((k == CXCursor_Constructor || k == CXCursor_Destructor) && name.indexOf('<') > 0) {
            name = name.substring(0, name.indexOf('<'));
        }
        return name;
    }

    List<String> paramTypes(CXCursor function) {
        List<String> types = new ArrayList<>();
        for (CXCursor p : children(function)) {
            if (clang_getCursorKind(p) == CXCursor_ParmDecl) {
                types.add(FunctionInfo.paramSpelling(str(clang_getTypeSpelling(clang_getCursorType(p)))));
            }
        }
        return types;
    }

    private String describeMethod(CXCursor m) {
        StringBuilder sb = new StringBuilder();
        if (clang_CXXMethod_isStatic(m) != 0) sb.append("static ");
        if (clang_CXXMethod_isVirtual(m) != 0) sb.append("virtual ");
        sb.append(functionName(m)).append('(').append(String.join(", ", paramTypes(m))).append(')');
        if (clang_CXXMethod_isConst(m) != 0) sb.append(" const");
        if (clang_CXXMethod_isPureVirtual(m) != 0) sb.append(" = 0");
        return sb.toString();
    }

    /** Namespace and enclosing classes of a declaration, from its semantic parents. */
    private record Scope(String namespace, String outerClasses) {}

    private Scope scope(CXCursor c) {
        List<String> namespaces = new ArrayList<>();
        List<String> outer = new ArrayList<>();
        CXCursor p = clang_getCursorSemanticParent(c);
        for (int guard = 0; guard < 64 && clang_Cursor_isNull(p) == 0; guard++) {
            int k = clang_getCursorKind(p);
            if (k == CXCursor_TranslationUnit) {
                break;
            }
            String name = str(clang_getCursorSpelling(p));
            if (k == CXCursor_Namespace) {
                namespaces.add(0, name.isEmpty() ? "(anonymous)" : name);
            } else if (isClassKind(k)) {
                outer.add(0, name);
            }
            p = clang_getCursorSemanticParent(p);
        }
        return new Scope(String.join("::", namespaces), String.join("::", outer));
    }

    /** Method ID of any function cursor (definition or declaration), built like FunctionInfo.id(). */
    String methodIdOf(CXCursor f) {
        CXCursor parent = clang_getCursorSemanticParent(f);
        String owner = isClassKind(clang_getCursorKind(parent)) ? qualifiedName(parent) : "";
        return FunctionInfo.methodId(scope(f).namespace, owner, functionName(f), paramTypes(clang_getCanonicalCursor(f)));
    }

    /** Solution-relative path of a file name reported by libclang, "" when outside the solution. */
    String relativePath(String fileName) {
        return relPathCache.computeIfAbsent(fileName, this::toRelative);
    }

    String qualifiedName(CXCursor classCursor) {
        Scope s = scope(classCursor);
        String name = str(clang_getCursorSpelling(classCursor));
        String inner = s.outerClasses.isEmpty() ? name : s.outerClasses + "::" + name;
        return s.namespace.isEmpty() ? inner : s.namespace + "::" + inner;
    }

    /**
     * dependencyTypes rule: pointers, references and const removed; unique_ptr/shared_ptr unwrapped; built-ins and
     * std:: types dropped except std::thread and std::atomic&lt;...&gt;. Returns the type as written, or null.
     */
    private String dependencyType(CXType t) {
        t = stripIndirection(t);
        String written = cleanType(str(clang_getTypeSpelling(t)));
        CXType canon = clang_getCanonicalType(t);
        String canonName = cleanType(str(clang_getTypeSpelling(canon)));
        if (canonName.startsWith("std::unique_ptr<") || canonName.startsWith("std::shared_ptr<")) {
            CXType inner = clang_Type_getTemplateArgumentAsType(canon, 0);
            if (inner.kind() == CXType_Invalid) {
                return null;
            }
            String innerWritten = cleanType(firstTemplateArgument(written));
            CXType innerCanon = clang_getCanonicalType(stripIndirection(inner));
            return keepOrDrop(innerCanon, cleanType(str(clang_getTypeSpelling(innerCanon))),
                    innerWritten.isEmpty() ? cleanType(str(clang_getTypeSpelling(inner))) : innerWritten);
        }
        return keepOrDrop(canon, canonName, written);
    }

    private static String keepOrDrop(CXType canon, String canonName, String written) {
        int k = canon.kind();
        if (k == CXType_Invalid || (k >= CXType_FirstBuiltin && k <= CXType_LastBuiltin)) {
            return null;
        }
        if (canonName.startsWith("std::")) {
            return canonName.equals("std::thread") || canonName.startsWith("std::atomic<") ? written : null;
        }
        return written;
    }

    private static CXType stripIndirection(CXType t) {
        for (int guard = 0; guard < 16; guard++) {
            int k = t.kind();
            if (k == CXType_Pointer || k == CXType_LValueReference || k == CXType_RValueReference) {
                t = clang_getPointeeType(t);
            } else if (k == CXType_ConstantArray || k == CXType_IncompleteArray) {
                t = clang_getArrayElementType(t);
            } else {
                break;
            }
        }
        return t;
    }

    /** "std::unique_ptr<Foo, D>" -> "Foo". */
    static String firstTemplateArgument(String spelling) {
        int start = spelling.indexOf('<');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int i = start + 1; i < spelling.length(); i++) {
            char ch = spelling.charAt(i);
            if (ch == '<') depth++;
            else if (ch == '>' && depth-- == 0) return spelling.substring(start + 1, i).trim();
            else if (ch == ',' && depth == 0) return spelling.substring(start + 1, i).trim();
        }
        return "";
    }

    /** Removes const/volatile, '&', '*' and extra spaces from a type spelling. */
    static String cleanType(String spelling) {
        String s = spelling.replaceAll("\\b(const|volatile)\\b", "").replaceAll("[&*]", "");
        return s.replaceAll("\\s+", " ").replaceAll("\\s*(<|>|,|::)\\s*", "$1").replace(",", ", ").trim();
    }

    private static String access(int a) {
        return switch (a) {
            case CX_CXXPublic -> "public";
            case CX_CXXProtected -> "protected";
            case CX_CXXPrivate -> "private";
            default -> "";
        };
    }

    static boolean isClassKind(int k) {
        return k == CXCursor_ClassDecl || k == CXCursor_StructDecl || k == CXCursor_ClassTemplate;
    }

    private static boolean isFunctionKind(int k) {
        return k == CXCursor_FunctionDecl || k == CXCursor_CXXMethod || k == CXCursor_Constructor
                || k == CXCursor_Destructor || k == CXCursor_FunctionTemplate;
    }

    private static boolean isSourceFile(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".c") || p.endsWith(".cpp") || p.endsWith(".cc") || p.endsWith(".cxx");
    }

    /** Project of a ClCompile file; else the project whose folder holds the file; else the project being parsed. */
    private String moduleFor(String relPath) {
        String key = relPath.toLowerCase(Locale.ROOT);
        String m = moduleByFile.get(key);
        if (m != null) {
            return m;
        }
        for (Map.Entry<String, String> e : projectFolders.entrySet()) {
            if (e.getValue().isEmpty() || key.startsWith(e.getValue() + "/")) {
                return e.getKey();
            }
        }
        return currentModule;
    }

    private boolean isInSolution(CXCursor c) {
        CXSourceLocation l = clang_getCursorLocation(c);
        if (clang_Location_isInSystemHeader(l) != 0) {
            return false;
        }
        return !location(c)[0].isEmpty();
    }

    /** {solution-relative path or "", line}. */
    private String[] location(CXCursor c) {
        CXFile file = new CXFile();
        int[] line = new int[1], col = new int[1], offset = new int[1];
        clang_getSpellingLocation(clang_getCursorLocation(c), file, line, col, offset);
        if (file.isNull()) {
            return new String[]{"", "0"};
        }
        String name = str(clang_getFileName(file));
        String rel = relPathCache.computeIfAbsent(name, this::toRelative);
        return new String[]{rel, String.valueOf(line[0])};
    }

    private String toRelative(String fileName) {
        if (fileName.isEmpty()) {
            return "";
        }
        Path p = Path.of(fileName).toAbsolutePath().normalize();
        try {
            p = p.toRealPath(); // disk casing, whatever case the #include used
        } catch (IOException ignored) {
            // keep the normalized name
        }
        if (!p.toString().toLowerCase(Locale.ROOT).startsWith(solutionPrefix)) {
            return "";
        }
        return solutionDir.relativize(p).toString().replace('\\', '/');
    }

    public static String str(CXString s) {
        BytePointer bp = clang_getCString(s);
        String result = bp == null ? "" : bp.getString();
        clang_disposeString(s);
        return result;
    }
}
