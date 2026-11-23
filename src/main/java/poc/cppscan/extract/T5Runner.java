package poc.cppscan.extract;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Prints the T5 evidence: call and entry point counts, and the calls of each entry point function. */
public class T5Runner {

    /** Also prints the calls of every function whose id contains one of {@code showCalls}. */
    public static void report(Extractor ex, List<String> showCalls) {
        report(ex);
        for (FunctionInfo f : ex.functions().values()) {
            if (showCalls.stream().anyMatch(s -> f.id().contains(s))) {
                printCalls(f);
            }
        }
    }

    public static void report(Extractor ex) {
        int calls = 0, exits = 0, async = 0, withPath = 0, external = 0;
        Map<String, Integer> byResolution = new TreeMap<>();
        Map<String, Integer> byStepKind = new TreeMap<>();
        for (FunctionInfo f : ex.functions().values()) {
            exits += f.exits.size();
            for (CallInfo c : f.calls) {
                calls++;
                byResolution.merge(c.resolution, 1, Integer::sum);
                if (c.async) async++;
                if (c.external) external++;
                if (!c.controlPath.isEmpty()) withPath++;
                c.controlPath.forEach(s -> byStepKind.merge(s.kind(), 1, Integer::sum));
            }
        }
        long withDoc = ex.functions().values().stream().filter(f -> !f.doc.isEmpty()).count();

        System.out.println("\n=== T5: CALLS AND ENTRY POINTS ===");
        System.out.println("Calls total:            " + calls);
        System.out.println("  by resolution:        " + byResolution);
        System.out.println("  into external decls:  " + external + " (third-party, or system anchor/async)");
        System.out.println("  async:                " + async);
        System.out.println("  inside control flow:  " + withPath + "  steps by kind " + byStepKind);
        System.out.println("Qualified virtual calls (Base::f(), EXACT): " + ex.qualifiedVirtualCalls());
        System.out.println("Exits (return/throw):   " + exits);
        System.out.println("Functions with doc:     " + withDoc);
        System.out.println("Entry points:           " + ex.entryPoints().size());
        ex.entryPoints().forEach(e -> System.out.println("  " + e.id() + "  -> " + e.methodId()));

        for (Extractor.EntryPoint e : ex.entryPoints()) {
            FunctionInfo f = ex.functions().values().stream()
                    .filter(fn -> fn.id().equals(e.methodId())).findFirst().orElse(null);
            if (f != null) printCalls(f);
        }
    }

    private static void printCalls(FunctionInfo f) {
        {
            System.out.println("\nCalls of " + f.id() + "  (" + f.filePath + ":" + f.line + ")");
            for (CallInfo c : f.calls) {
                String target = c.targetMethodIds.isEmpty()
                        ? (c.targetClass.isEmpty() ? "" : c.targetClass + "::") + c.targetMethod
                        : String.join(" | ", c.targetMethodIds);
                System.out.printf("  seq %2d  line %3d  %-10s %s%s%s%n", c.seq, c.line, c.resolution, target,
                        c.async ? "  ASYNC" : "", c.controlPath.isEmpty() ? "" : "  path " + c.controlPath);
            }
            f.exits.forEach(x -> System.out.println("  exit " + x.kind() + " line " + x.line()
                    + (x.controlPath().isEmpty() ? "" : "  path " + x.controlPath())));
        }
    }
}
