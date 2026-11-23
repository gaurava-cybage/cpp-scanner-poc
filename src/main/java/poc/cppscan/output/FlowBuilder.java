package poc.cppscan.output;

import poc.cppscan.extract.CallInfo;
import poc.cppscan.extract.Extractor;
import poc.cppscan.extract.FunctionInfo;
import poc.cppscan.model.Facts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One flow file per entry point (docs/output-format.md, "Flow files"): the methods reachable from the entry point
 * up to maxDepth call levels, as depth-first steps following each method's calls by seq.
 * Each method's calls are expanded once; later calls to it are still steps but are not expanded again.
 */
public class FlowBuilder {

    private final Map<String, FunctionInfo> functionsById = new LinkedHashMap<>();
    private final Map<String, List<Facts.Method>> methodsById;
    private final int maxDepth;

    private final List<Facts.Step> steps = new ArrayList<>();
    private final Map<String, Facts.Participant> participants = new LinkedHashMap<>(); // first-appearance order
    private final Set<String> reached = new TreeSet<>();
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, Integer> omitted = new LinkedHashMap<>();
    private boolean truncated;

    public FlowBuilder(Extractor ex, List<Facts.Method> methods, int maxDepth) {
        // Sorted by id/file/line, so the first function wins when two share an id.
        ex.functions().values().stream()
                .sorted(java.util.Comparator.comparing(FunctionInfo::id).thenComparing(f -> f.filePath)
                        .thenComparingInt(f -> f.line))
                .forEach(f -> functionsById.putIfAbsent(f.id(), f));
        this.methodsById = FactsBuilder.byId(methods);
        this.maxDepth = maxDepth;
        ex.sqliteRule.dataSources.forEach(d ->
                urlsByModule.computeIfAbsent(d.module(), k -> new ArrayList<>()).add(d.url()));
        urlsByModule.replaceAll((k, v) -> v.stream().distinct().sorted().toList());
    }

    private final Map<String, List<String>> urlsByModule = new LinkedHashMap<>();

    /** File name for an entry point id: every character other than a letter, digit or '-' becomes '-'. */
    public static String fileName(String entryPointId) {
        return entryPointId.replaceAll("[^A-Za-z0-9-]", "-") + ".json";
    }

    public Facts.Flow build(Facts.EntryPoint entry, String generatedAt) {
        steps.clear();
        participants.clear();
        reached.clear();
        expanded.clear();
        omitted.clear();
        omitted.put("std", 0);
        omitted.put("logging", 0);
        omitted.put("accessors", 0);
        truncated = false;

        FunctionInfo start = functionsById.get(entry.methodId());
        if (start != null) {
            visit(start, 0);
        }
        List<Facts.Method> methods = new ArrayList<>();
        reached.forEach(id -> methods.addAll(methodsById.getOrDefault(id, List.of())));
        return new Facts.Flow(new Facts.FlowEntry(entry.id(), entry.kind(), entry.methodId()), generatedAt, maxDepth,
                new ArrayList<>(participants.values()), new ArrayList<>(steps), methods, new LinkedHashMap<>(omitted),
                truncated);
    }

    private void visit(FunctionInfo fn, int depth) {
        if (!expanded.add(fn.id())) {
            return;
        }
        reached.add(fn.id());
        addParticipant(fn);
        boolean afterSqlStep = false; // a prepare/exec/open step was the last step of this function
        for (CallInfo call : fn.calls) {
            String category = omitCategory(call);
            if (category != null) {
                omitted.merge(category, 1, Integer::sum);
                continue;
            }
            boolean sqlite = "sqlite3".equals(call.targetClass);
            if (sqlite) {
                // Consecutive SQLite calls (step, bind, column, finalize, ...) fold into the prepare/exec before them.
                boolean leading = call.targetMethod.startsWith("sqlite3_prepare") || call.targetMethod.equals("sqlite3_exec")
                        || call.targetMethod.startsWith("sqlite3_open");
                if (!leading && afterSqlStep) {
                    continue;
                }
                afterSqlStep = leading;
                if (depth + 1 <= maxDepth) {
                    addDatabaseParticipant(fn.module);
                }
            } else {
                // Not reset by other calls: in "prepare; while (step) { ParseRow(); column...; }" the column calls
                // still belong to the prepare step.
                if ("winsock".equals(call.targetClass) && depth + 1 <= maxDepth) {
                    participants.putIfAbsent("SOCKET winsock", new Facts.Participant("winsock", "SOCKET", ""));
                }
            }
            for (String target : targets(call)) {
                if (depth + 1 > maxDepth) {
                    truncated = true;
                    continue;
                }
                steps.add(new Facts.Step(depth + 1, fn.id(), target, call.seq, FactsBuilder.steps(call.controlPath),
                        call.async, call.line, fn.filePath));
                FunctionInfo callee = functionsById.get(target);
                if (callee != null) {
                    visit(callee, depth + 1);
                }
            }
        }
    }

    /**
     * Step targets: the defined methods among targetMethodIds (for a VIRTUAL call: the implementations), else the
     * declared id; for calls outside the solution "&lt;targetClass&gt;#&lt;targetMethod&gt;".
     */
    private List<String> targets(CallInfo call) {
        if (call.targetMethodIds.isEmpty()) {
            return List.of(call.targetClass + "#" + call.targetMethod);
        }
        List<String> defined = call.targetMethodIds.stream().filter(functionsById::containsKey).toList();
        return defined.isEmpty() ? List.of(call.targetMethodIds.get(0)) : defined;
    }

    /** std:: calls (except async thread starts), logging (names with log/trace/debug), trivial accessors. */
    private String omitCategory(CallInfo call) {
        if (!call.async && (call.targetClass.equals("std") || call.targetClass.startsWith("std::"))) {
            return "std";
        }
        String names = (call.targetClass + " " + call.targetMethod).toLowerCase(Locale.ROOT);
        if (names.contains("log") || names.contains("trace") || names.contains("debug")) {
            return "logging";
        }
        if (!call.targetMethodIds.isEmpty()) {
            FunctionInfo callee = functionsById.get(call.targetMethodIds.get(0));
            if (callee != null && isAccessor(callee)) {
                return "accessors";
            }
        }
        return null;
    }

    /**
     * A trivial getter or setter: a one-line class method with no calls and no branches that is either a getter
     * (no parameters, returns a value) or a setter (one parameter, returns void).
     */
    static boolean isAccessor(FunctionInfo f) {
        boolean getter = f.paramTypes.isEmpty() && !f.returnType.isEmpty() && !f.returnType.equals("void");
        boolean setter = f.paramTypes.size() == 1 && f.returnType.equals("void");
        return !f.className.isEmpty() && (getter || setter) && f.line == f.endLine && f.calls.isEmpty()
                && f.exits.stream().allMatch(x -> x.controlPath().isEmpty());
    }

    /**
     * DATABASE participant, id = the data source url. Which connection a call uses is not tracked, so the url of the
     * caller's module is used: when the module opens several different urls, the first one in sort order.
     */
    private void addDatabaseParticipant(String module) {
        List<String> urls = urlsByModule.getOrDefault(module, List.of());
        String url = urls.isEmpty() ? "sqlite:" + poc.cppscan.rules.SqliteRule.DYNAMIC : urls.get(0);
        participants.putIfAbsent("DATABASE " + url, new Facts.Participant(url, "DATABASE", ""));
    }

    private void addParticipant(FunctionInfo fn) {
        if (!fn.className.isEmpty()) {
            participants.putIfAbsent("CLASS " + fn.className, new Facts.Participant(fn.className, "CLASS", fn.module));
        } else {
            participants.putIfAbsent("MODULE " + fn.module, new Facts.Participant(fn.module, "MODULE", fn.module));
        }
    }
}
