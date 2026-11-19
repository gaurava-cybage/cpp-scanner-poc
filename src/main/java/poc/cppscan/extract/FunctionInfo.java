package poc.cppscan.extract;

import java.util.ArrayList;
import java.util.List;

/** One function or method definition (later codeFlow.methods[]). T5 adds calls, exits and doc here. */
public class FunctionInfo {
    public String usr;
    public String name;
    /** Namespace joined with "::", "" for the global namespace. */
    public String packageName;
    /** Qualified owning class ("ns::Outer::Inner"), "" for free functions. */
    public String className;
    /** USR of the owning class, "" for free functions. */
    public String classUsr;
    public String filePath;
    public int line;
    /** Last line of the definition (to spot one-line accessors). */
    public int endLine;
    public List<String> paramTypes = new ArrayList<>();
    public String returnType;
    public boolean isVirtual;
    public boolean isPureVirtual;
    public boolean isStatic;
    public boolean isConst;
    public String module;

    // T5
    public String doc = "";
    public List<CallInfo> calls = new ArrayList<>();
    public List<CallInfo.ExitInfo> exits = new ArrayList<>();
    /** T7: one of its resolved SQL strings contains BEGIN, COMMIT or ROLLBACK. */
    public boolean transactional;
    /** Marked __declspec(dllexport) on its definition or first declaration. */
    public boolean dllExport;
    /** USRs of the methods this one directly overrides. */
    public List<String> overriddenUsrs = new ArrayList<>();

    public String id() {
        return methodId(packageName, className, name, paramTypes);
    }

    /**
     * The one method-ID builder for T4 and T5 (docs/output-format.md): "ns::Class#name(T1, T2)",
     * free functions "ns#name(...)", global ones "#name(...)". {@code className} is already qualified.
     */
    public static String methodId(String packageName, String className, String name, List<String> paramTypes) {
        String owner = !className.isEmpty() ? className : packageName;
        return owner + "#" + name + "(" + String.join(", ", paramTypes) + ")";
    }

    /** libclang spelling with the space before '&' and '*' removed: "const std::string &" -> "const std::string&". */
    public static String paramSpelling(String typeSpelling) {
        return typeSpelling.replaceAll("\\s+(?=[&*])", "");
    }
}
