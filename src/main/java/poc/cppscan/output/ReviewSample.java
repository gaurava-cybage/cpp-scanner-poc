package poc.cppscan.output;

import poc.cppscan.model.Facts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * out/review-sample.md: a fixed-seed random sample of calls and classes for the C++ developer to confirm
 * (success criterion "Extraction is correct"). The same facts always give the same sample.
 */
public class ReviewSample {

    public static final long SEED = 20261119L;
    public static final int CALLS = 20;
    public static final int CLASSES = 10;

    private record CallRow(Facts.Method method, Facts.Call call) {}

    public static String markdown(String solutionName, List<Facts.Method> methods, List<Facts.Component> components) {
        List<CallRow> allCalls = new ArrayList<>();
        for (Facts.Method m : methods) {        // methods and calls are already in output (sorted) order
            for (Facts.Call c : m.calls()) {
                allCalls.add(new CallRow(m, c));
            }
        }
        List<CallRow> calls = sample(allCalls, CALLS).stream()
                .sorted(Comparator.comparing((CallRow r) -> r.method().sourceFile())
                        .thenComparingInt(r -> r.call().line()).thenComparingInt(r -> r.call().seq()))
                .toList();
        List<Facts.Component> classes = sample(components, CLASSES).stream()
                .sorted(Comparator.comparing(Facts.Component::filePath).thenComparing(Facts.Component::className))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("# Extraction review sample: ").append(solutionName).append("\n\n");
        sb.append("Random sample (fixed seed ").append(SEED).append(") of ").append(calls.size()).append(" of ")
                .append(allCalls.size()).append(" calls and ").append(classes.size()).append(" of ")
                .append(components.size()).append(" classes from `facts.json`.\n");
        sb.append("Pass mark: at least 18 of 20 calls and 10 of 10 classes confirmed.\n\n");

        sb.append("## Calls\n\n");
        sb.append("For each row: is there a call at this line, inside this function, to this target? "
                + "Are the order within the function (`seq`), the enclosing branches and loops, and `async` right?\n\n");
        sb.append("| # | Location | In function | Calls | Kind / resolution | seq | Control path | async | OK? |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        int n = 1;
        for (CallRow r : calls) {
            Facts.Call c = r.call();
            String target = c.targetClass() + "#" + c.targetMethod(); // same form as flow-file steps
            sb.append("| ").append(n++)
                    .append(" | `").append(r.method().sourceFile()).append(':').append(c.line()).append('`')
                    .append(" | `").append(cell(r.method().id())).append('`')
                    .append(" | `").append(cell(target)).append('`')
                    .append(" | ").append(c.kind()).append(" / ").append(c.resolution())
                    .append(" | ").append(c.seq())
                    .append(" | ").append(controlPath(c.controlPath()))
                    .append(" | ").append(c.async() ? "yes" : "no")
                    .append(" | [ ] |\n");
        }

        sb.append("\n## Classes\n\n");
        sb.append("For each row: are the name, namespace, file, kind, base classes and member field types right?\n\n");
        sb.append("| # | Class | Namespace | File | Type | Annotations | Base classes | Field types used | OK? |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        n = 1;
        for (Facts.Component k : classes) {
            sb.append("| ").append(n++)
                    .append(" | `").append(cell(k.className())).append('`')
                    .append(" | `").append(cell(k.packageName())).append('`')
                    .append(" | `").append(k.filePath()).append('`')
                    .append(" | ").append(k.type())
                    .append(" | ").append(list(k.annotations()))
                    .append(" | ").append(list(k.baseClasses().stream()
                            .map(b -> b.access() + (b.virtual() ? " virtual " : " ") + b.name()).toList()))
                    .append(" | ").append(list(k.dependencyTypes()))
                    .append(" | [ ] |\n");
        }
        return sb.toString();
    }

    /** The first {@code count} items after a seeded shuffle of a copy; all items when there are fewer. */
    static <T> List<T> sample(List<T> items, int count) {
        List<T> copy = new ArrayList<>(items);
        Collections.shuffle(copy, new Random(SEED));
        return copy.subList(0, Math.min(count, copy.size()));
    }

    private static String controlPath(List<Facts.ControlStep> path) {
        if (path.isEmpty()) {
            return "-";
        }
        return path.stream().map(s -> {
            String step = s.kind() + (s.condition().isEmpty() ? "" : " (" + s.condition() + ")");
            return "`" + cell(step) + "`" + (s.branch().isEmpty() ? "" : " " + cell(s.branch()));
        }).collect(Collectors.joining(" &gt; "));
    }

    private static String list(List<String> items) {
        return items.isEmpty() ? "-" : items.stream().map(s -> "`" + cell(s) + "`").collect(Collectors.joining(", "));
    }

    /** One table cell: whitespace collapsed, pipes and backticks escaped. */
    private static String cell(String s) {
        return s.replaceAll("\\s+", " ").replace("|", "\\|").replace("`", "'").trim();
    }
}
