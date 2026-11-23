package poc.cppscan.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** One class or struct (later modules[].components[]). Field names follow docs/output-format.md. */
public class ClassInfo {
    public String usr;
    /** Simple name; for a nested class "Outer::Inner". */
    public String className;
    /** Namespace joined with "::", "" for the global namespace. */
    public String packageName;
    public String filePath;
    public int line;
    public TreeSet<String> sourceFiles = new TreeSet<>();
    public String type; // INTERFACE, ENTITY, EXPORTED_API, COMPONENT
    /** In the order of the spec: final, abstract, struct, dllexport. */
    public List<String> annotations = new ArrayList<>();
    public List<BaseClass> baseClasses = new ArrayList<>();
    public TreeSet<String> dependencyTypes = new TreeSet<>();
    public String module;

    // Not in the JSON; kept for the T4 report and for T5.
    public List<String> fields = new ArrayList<>();
    public List<String> methods = new ArrayList<>();

    public record BaseClass(String name, String access, boolean isVirtual) {}

    /** "ns::Outer::Inner", the className used by codeFlow.methods. */
    public String qualifiedName() {
        return packageName.isEmpty() ? className : packageName + "::" + className;
    }
}
