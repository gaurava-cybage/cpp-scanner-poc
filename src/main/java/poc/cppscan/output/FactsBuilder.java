package poc.cppscan.output;

import poc.cppscan.extract.CallInfo;
import poc.cppscan.extract.ClassInfo;
import poc.cppscan.extract.Extractor;
import poc.cppscan.extract.FunctionInfo;
import poc.cppscan.model.Dependency;
import poc.cppscan.model.Facts;
import poc.cppscan.msbuild.SourceFile;
import poc.cppscan.parse.ClangParser;
import poc.cppscan.parse.T3Runner;
import poc.cppscan.rules.SqlFiles;
import poc.cppscan.rules.SqlText;
import poc.cppscan.rules.SqliteRule;
import poc.cppscan.rules.WinsockRule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Builds facts.json (docs/output-format.md) from the T2–T5 results. Every list is sorted or in .sln order. */
public class FactsBuilder {

    /** What T2 knows about one project. */
    public record ModuleInput(String name, String relativePath, String buildFile, String configurationType,
                              int fileCount, List<Dependency> dependencies) {}

    private static final Set<String> ENTRY_KINDS = Set.of("MAIN", "WINMAIN", "DLL_MAIN");

    public static Facts build(String solutionName, Path solutionDir, List<ModuleInput> modules,
                              Collection<SourceFile> files, List<T3Runner.FileResult> results, Extractor ex,
                              String generatedAt, long scanSeconds) {
        List<Facts.Module> outModules = new ArrayList<>();
        for (ModuleInput m : modules) {
            List<Facts.Component> components = ex.classes().values().stream()
                    .filter(c -> c.module.equals(m.name()))
                    .sorted(Comparator.comparing(ClassInfo::qualifiedName).thenComparing(c -> c.filePath))
                    .map(FactsBuilder::component)
                    .toList();
            outModules.add(new Facts.Module(m.name(), solutionName, m.name(), "unspecified",
                    packaging(m.configurationType()), m.relativePath(), m.buildFile(), m.fileCount() > 0,
                    m.dependencies(), components, List.of(), deploymentRole(m.configurationType())));
        }

        List<Facts.EntryPoint> entryPoints = ex.entryPoints().stream()
                .map(e -> new Facts.EntryPoint(e.id(), e.kind(), e.module(), e.className(), e.methodId()))
                .toList();
        List<Facts.Method> methods = methods(ex);
        int callCount = methods.stream().mapToInt(m -> m.calls().size()).sum();
        List<Dependency> aggregated = aggregate(modules);
        List<Facts.Diagnostic> diagnostics = diagnostics(results, solutionDir);

        Facts.Database database = database(ex, solutionDir, modules);
        Facts.Integrations integrations = new Facts.Integrations(List.of(), List.of(), List.of(), ipc(ex));

        Facts.Summary summary = new Facts.Summary(
                modules.size(),
                (int) modules.stream().filter(m -> "Application".equals(m.configurationType())).count(),
                ex.classes().size(), aggregated.size(),
                database.tables().size(), database.dataSources().size(), 0, 0, 0, 0,
                entryPoints.size(), methods.size(), callCount,
                results.size(), diagnostics.size(), scanSeconds);

        return new Facts(solutionName, "unspecified", solutionDir.toString().replace('\\', '/'), "MSBUILD",
                generatedAt, "C++", "0.1-poc", languages(files), outModules, topology(modules), aggregated,
                database, integrations,
                List.of(), summary,
                new Facts.CodeFlow(1, solutionName, "SOURCE", entryPoints, methods),
                diagnostics);
    }

    // ---------------------------------------------------------------- sections

    /** One entry per language standard, by file count of the ClCompile files that use it. */
    static List<Facts.Language> languages(Collection<SourceFile> files) {
        Map<String, Integer> byStandard = new TreeMap<>();
        for (SourceFile f : files) {
            String std = f.clangArgs().stream().filter(a -> a.startsWith("/std:")).map(a -> a.substring(5))
                    .findFirst().orElse("default");
            byStandard.merge(std, 1, Integer::sum);
        }
        List<Facts.Language> result = new ArrayList<>();
        byStandard.forEach((std, n) -> result.add(new Facts.Language("C++", std, n)));
        return result;
    }

    static Facts.DeploymentTopology topology(List<ModuleInput> modules) {
        List<String> apps = names(modules, "Application");
        List<String> shared = names(modules, "DynamicLibrary");
        List<String> internal = names(modules, "StaticLibrary");
        List<String> unclear = modules.stream()
                .filter(m -> !Set.of("Application", "DynamicLibrary", "StaticLibrary").contains(m.configurationType()))
                .map(ModuleInput::name).toList();
        boolean hasLibraries = !shared.isEmpty() || !internal.isEmpty();
        String style = apps.isEmpty() ? "LIBRARY"
                : apps.size() >= 2 ? "MULTI_APPLICATION"
                : hasLibraries ? "MODULAR_MONOLITH" : "MONOLITH";
        return new Facts.DeploymentTopology(style, apps, shared, internal, List.of(), unclear);
    }

    private static List<String> names(List<ModuleInput> modules, String type) {
        return modules.stream().filter(m -> type.equals(m.configurationType())).map(ModuleInput::name).toList();
    }

    /** Non-internal dependencies of all modules, de-duplicated by groupId + artifactId, sorted, without internal. */
    static List<Dependency> aggregate(List<ModuleInput> modules) {
        Map<String, Dependency> byKey = new TreeMap<>();
        for (ModuleInput m : modules) {
            for (Dependency d : m.dependencies()) {
                if (!Boolean.TRUE.equals(d.internal())) {
                    byKey.putIfAbsent(d.groupId() + "\u0000" + d.artifactId().toLowerCase(),
                            new Dependency(d.groupId(), d.artifactId(), d.version(), d.scope(), d.linkage(), null));
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** The first error of each file that did not parse clean, sorted by file. */
    static List<Facts.Diagnostic> diagnostics(List<T3Runner.FileResult> results, Path solutionDir) {
        List<Facts.Diagnostic> out = new ArrayList<>();
        for (T3Runner.FileResult r : results) {
            if (r.result().isClean()) {
                continue;
            }
            String file = relative(solutionDir, r.file().fullPath());
            if (r.result().errorCode != 0) {
                out.add(new Facts.Diagnostic(file, "FATAL", "libclang error code " + r.result().errorCode));
                continue;
            }
            r.result().diagnostics.stream().filter(ClangParser.Diagnostic::isError).findFirst()
                    .ifPresent(d -> out.add(new Facts.Diagnostic(file, d.severity, d.message)));
        }
        out.sort(Comparator.comparing(Facts.Diagnostic::file));
        return out;
    }

    private static String relative(Path solutionDir, String fullPath) {
        Path p = Path.of(fullPath).toAbsolutePath().normalize();
        String rel = p.startsWith(solutionDir) ? solutionDir.relativize(p).toString() : p.toString();
        return rel.replace('\\', '/');
    }

    // ---------------------------------------------------------------- database and ipc (T7)

    /**
     * dataSources from sqlite3_open* calls (by file, line); tables from CREATE TABLE in resolved SQL in the code
     * (EMBEDDED_SQL, by file and line) and then in .sql files (SQL, by file and position).
     */
    static Facts.Database database(Extractor ex, Path solutionDir, List<ModuleInput> modules) {
        List<Facts.DataSource> dataSources = ex.sqliteRule.dataSources.stream()
                .sorted(Comparator.comparing(SqliteRule.DataSourceFact::sourceFile)
                        .thenComparingInt(SqliteRule.DataSourceFact::line))
                .map(d -> new Facts.DataSource(d.module(), d.url(), "", d.sourceFile(), d.line(), "sqlite3", d.expression()))
                .toList();

        List<Facts.Table> tables = new ArrayList<>();
        ex.sqliteRule.statements.stream()
                .filter(s -> s.sql() != null)
                .sorted(Comparator.comparing(SqliteRule.SqlFact::sourceFile).thenComparingInt(SqliteRule.SqlFact::line))
                .forEach(s -> SqlText.createTables(s.sql()).forEach(t ->
                        tables.add(table(t, "EMBEDDED_SQL", s.module(), s.sourceFile(), s.line()))));

        List<String> scripts;
        try {
            scripts = SqlFiles.find(solutionDir);
            for (String script : scripts) {
                String text = SqlFiles.read(solutionDir, script);
                for (SqlText.TableDef t : SqlText.createTables(text)) {
                    tables.add(table(t, "SQL", moduleOfPath(script, modules), script, SqlFiles.lineOf(text, t.offset())));
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("Cannot read .sql files: " + e.getMessage());
            scripts = List.of();
        }
        return new Facts.Database(dataSources, tables, scripts.isEmpty() ? "NONE" : "SQL_SCRIPTS", scripts);
    }

    private static Facts.Table table(SqlText.TableDef t, String source, String module, String file, int line) {
        return new Facts.Table(t.name(), source, module, file, line,
                t.columns().stream().map(c -> new Facts.Column(c.name(), c.type(), c.primaryKey(), c.nullable(),
                        c.references())).toList(),
                t.relationships().stream().map(r -> new Facts.Relationship(r.type(), r.column(), r.references())).toList());
    }

    /** The project whose folder contains the path (longest folder wins), or "". */
    static String moduleOfPath(String relativePath, List<ModuleInput> modules) {
        String p = relativePath.toLowerCase(java.util.Locale.ROOT);
        String best = "";
        int bestLength = -1;
        for (ModuleInput m : modules) {
            String folder = m.relativePath().toLowerCase(java.util.Locale.ROOT);
            if ((folder.isEmpty() || p.startsWith(folder + "/")) && folder.length() > bestLength) {
                best = m.name();
                bestLength = folder.length();
            }
        }
        return best;
    }

    static List<Facts.Ipc> ipc(Extractor ex) {
        return ex.winsockRule.ipc.stream()
                .sorted(Comparator.comparing(WinsockRule.IpcFact::sourceFile).thenComparingInt(WinsockRule.IpcFact::line)
                        .thenComparing(WinsockRule.IpcFact::operation))
                .map(i -> new Facts.Ipc("TCP_SOCKET", i.role(), i.operation(), i.module(), i.methodId(), i.sourceFile(),
                        i.line(), "CONFIRMED"))
                .toList();
    }

    // ---------------------------------------------------------------- components and methods

    static Facts.Component component(ClassInfo c) {
        return new Facts.Component(c.className, c.packageName, c.filePath, List.copyOf(c.sourceFiles), c.type,
                List.copyOf(c.annotations),
                c.baseClasses.stream().map(b -> new Facts.BaseClass(b.name(), b.access(), b.isVirtual())).toList(),
                List.copyOf(c.dependencyTypes));
    }

    /** codeFlow.methods: every function defined in project files, sorted by id (then file and line). */
    static List<Facts.Method> methods(Extractor ex) {
        Map<String, String> entryKindById = new LinkedHashMap<>();
        ex.entryPoints().forEach(e -> entryKindById.put(e.methodId(), e.kind()));
        return ex.functions().values().stream()
                .sorted(Comparator.comparing(FunctionInfo::id).thenComparing(f -> f.filePath).thenComparingInt(f -> f.line))
                .map(f -> method(f, ex, entryKindById.get(f.id())))
                .toList();
    }

    static Facts.Method method(FunctionInfo f, Extractor ex, String entryKind) {
        String componentType;
        ClassInfo owner = ex.classes().get(f.classUsr);
        if (owner != null) {
            componentType = owner.type;
        } else if (entryKind != null && ENTRY_KINDS.contains(entryKind)) {
            componentType = "APPLICATION_ENTRY";
        } else if (f.dllExport) {
            componentType = "EXPORTED_API";
        } else {
            componentType = "";
        }
        return new Facts.Method(f.id(), f.module, f.className, componentType, f.name, List.copyOf(f.paramTypes),
                f.returnType, f.isVirtual, f.doc, f.transactional ? Boolean.TRUE : null,
                f.calls.stream().map(FactsBuilder::call).toList(),
                f.exits.stream().map(x -> new Facts.Exit(x.kind(), steps(x.controlPath()), x.line())).toList(),
                f.filePath, f.line);
    }

    static Facts.Call call(CallInfo c) {
        List<String> tables = c.tables == null ? null : List.copyOf(c.tables);
        String table = tables != null && tables.size() == 1 ? tables.get(0) : null;
        return new Facts.Call(c.kind, c.targetClass, c.targetMethod, List.copyOf(c.targetMethodIds), c.resolution,
                c.sql, c.operation, tables, table, c.expression, c.seq, steps(c.controlPath), c.async, c.line);
    }

    static List<Facts.ControlStep> steps(List<CallInfo.ControlStep> path) {
        return path.stream().map(s -> new Facts.ControlStep(s.kind(), s.condition(), s.branch(), s.line())).toList();
    }

    static String packaging(String configurationType) {
        return switch (configurationType) {
            case "Application" -> "executable";
            case "DynamicLibrary" -> "shared-library";
            case "StaticLibrary" -> "static-library";
            default -> "unknown";
        };
    }

    static String deploymentRole(String configurationType) {
        return switch (configurationType) {
            case "Application" -> "DEPLOYABLE_APPLICATION";
            case "DynamicLibrary" -> "SHARED_LIBRARY";
            case "StaticLibrary" -> "INTERNAL_LIBRARY";
            default -> "UNCLEAR";
        };
    }

    /** Groups methods by id (two functions in different anonymous namespaces can share one). */
    static Map<String, List<Facts.Method>> byId(List<Facts.Method> methods) {
        return methods.stream().collect(Collectors.groupingBy(Facts.Method::id, LinkedHashMap::new, Collectors.toList()));
    }
}
