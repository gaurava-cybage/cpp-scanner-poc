package poc.cppscan.extract;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Prints the T4 evidence: counts per project and every class with its bases and members. */
public class T4Runner {

    /** Counts always; every class and function only with {@code details} (--details). */
    public static void report(Extractor ex, List<String> projectOrder, boolean details) {
        Map<String, Long> classCounts = ex.classes().values().stream()
                .collect(Collectors.groupingBy(c -> c.module, TreeMap::new, Collectors.counting()));
        Map<String, Long> functionCounts = ex.functions().values().stream()
                .collect(Collectors.groupingBy(f -> f.module, TreeMap::new, Collectors.counting()));

        System.out.println("\n=== T4: CLASSES AND FUNCTIONS ===");
        System.out.printf("%-14s %8s %10s%n", "Project", "Classes", "Functions");
        for (String p : projectOrder) {
            System.out.printf("%-14s %8d %10d%n", p, classCounts.getOrDefault(p, 0L), functionCounts.getOrDefault(p, 0L));
        }
        System.out.printf("%-14s %8d %10d%n", "TOTAL", ex.classes().size(), ex.functions().size());
        System.out.println("Fields with unresolved type (missing header): " + ex.unresolvedFieldTypes());
        if (!details) {
            return;
        }

        Comparator<ClassInfo> order = Comparator.comparing((ClassInfo c) -> c.module).thenComparing(ClassInfo::qualifiedName);
        for (ClassInfo c : ex.classes().values().stream().sorted(order).toList()) {
            System.out.println();
            System.out.println("[" + c.module + "] " + c.qualifiedName() + "  " + c.type
                    + (c.annotations.isEmpty() ? "" : "  " + c.annotations));
            System.out.println("    filePath:        " + c.filePath + ":" + c.line);
            System.out.println("    sourceFiles:     " + c.sourceFiles);
            System.out.println("    baseClasses:     " + c.baseClasses.stream()
                    .map(b -> b.access() + (b.isVirtual() ? " virtual " : " ") + b.name()).toList());
            System.out.println("    dependencyTypes: " + c.dependencyTypes);
            c.fields.forEach(f -> System.out.println("    field   " + f));
            c.methods.forEach(m -> System.out.println("    method  " + m));
        }

        System.out.println("\nFunctions (id -> file:line):");
        ex.functions().values().stream().sorted(Comparator.comparing(FunctionInfo::id))
                .forEach(f -> System.out.println("  [" + f.module + "] " + f.id() + "  -> " + f.filePath + ":" + f.line
                        + (f.isVirtual ? "  virtual" : "") + (f.isStatic ? "  static" : "") + (f.isConst ? "  const" : "")));
    }
}
