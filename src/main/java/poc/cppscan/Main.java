package poc.cppscan;

import poc.cppscan.extract.Extractor;
import poc.cppscan.extract.T4Runner;
import poc.cppscan.extract.T5Runner;
import poc.cppscan.model.Dependency;
import poc.cppscan.model.Facts;
import poc.cppscan.msbuild.DependencyCollector;
import poc.cppscan.msbuild.MSBuildRunner;
import poc.cppscan.msbuild.SolutionParser;
import poc.cppscan.msbuild.SourceFile;
import poc.cppscan.output.FactsBuilder;
import poc.cppscan.output.FlowBuilder;
import poc.cppscan.output.JsonWriter;
import poc.cppscan.output.ReviewSample;
import poc.cppscan.output.SqlReview;
import poc.cppscan.parse.T3Runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Main {

    /** Command-line options that affect the scan itself. */
    public record Options(Path solution, String configuration, String platform, List<String> properties,
                          boolean printArgs, boolean details, List<String> showCalls) {}

    /** The facts plus the extractor (flow files and sql-review.md need it). */
    public record ScanResult(Facts facts, Extractor extractor) {}

    public static void main(String[] args) {
        long mainStart = System.nanoTime();
        String solutionPath = null;
        String configuration = "Release";
        String platform = "x64";
        boolean printArgs = false;
        boolean details = false;
        List<String> showCalls = new ArrayList<>();
        List<String> properties = new ArrayList<>();
        Path out = Path.of("out", "facts.json");
        Path flowsDir = null;
        int maxDepth = 6;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--solution" -> solutionPath = args[++i];
                case "--config" -> configuration = args[++i];
                case "--platform" -> platform = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--flows" -> flowsDir = Path.of(args[++i]);
                case "--max-depth" -> maxDepth = Integer.parseInt(args[++i]);
                case "--property" -> properties.add(args[++i]);
                case "--print-args" -> printArgs = true;
                case "--details" -> details = true;
                case "--show-calls" -> showCalls.add(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(1);
                }
            }
        }
        if (solutionPath == null) {
            System.err.println("Usage: java -jar cpp-scanner-poc.jar --solution <path.sln> [--config Release] [--platform x64]"
                    + " [--out out\\facts.json] [--flows <dir next to --out>\\flows] [--max-depth 6]"
                    + " [--property NAME=VALUE]... [--print-args] [--details] [--show-calls <method id part>]");
            System.exit(1);
        }
        Path outDir = out.toAbsolutePath().getParent();
        if (flowsDir == null) {
            flowsDir = outDir.resolve("flows");
        }

        try {
            ScanResult result = scan(new Options(Path.of(solutionPath), configuration, platform, properties,
                    printArgs, details, showCalls));
            Facts facts = result.facts();

            JsonWriter.write(facts, out);
            System.out.println("\n=== T6/T7: OUTPUT ===");
            System.out.println("Wrote " + out.toAbsolutePath().normalize());

            Files.createDirectories(flowsDir);
            try (var old = Files.newDirectoryStream(flowsDir, "*.json")) {
                for (Path p : old) {
                    Files.delete(p); // stale flow files from a previous scan (our own output folder)
                }
            }
            FlowBuilder flows = new FlowBuilder(result.extractor(), facts.codeFlow().methods(), maxDepth);
            for (Facts.EntryPoint ep : facts.codeFlow().entryPoints()) {
                Facts.Flow flow = flows.build(ep, facts.generatedAt());
                Path file = flowsDir.resolve(FlowBuilder.fileName(ep.id()));
                JsonWriter.write(flow, file);
                System.out.println("Wrote " + file.toAbsolutePath().normalize() + "  (" + flow.steps().size()
                        + " steps, " + flow.methods().size() + " methods, omitted " + flow.omitted()
                        + (flow.truncated() ? ", truncated" : "") + ")");
            }

            Path review = outDir.resolve("sql-review.md");
            Files.writeString(review, SqlReview.markdown(facts.targetProjectName(), result.extractor().sqliteRule));
            System.out.println("Wrote " + review.normalize());

            Path sample = outDir.resolve("review-sample.md");
            Files.writeString(sample, ReviewSample.markdown(facts.targetProjectName(), facts.codeFlow().methods(),
                    facts.modules().stream().flatMap(m -> m.components().stream()).toList()));
            System.out.println("Wrote " + sample.normalize());
            System.out.printf("Total time including output: %.1f s%n", (System.nanoTime() - mainStart) / 1e9);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** T2–T7: read the solution, parse every file, extract, and build the facts. Writes nothing. */
    public static ScanResult scan(Options o) throws Exception {
        long startNanos = System.nanoTime();
        Path sln = o.solution().toAbsolutePath().normalize();
        if (!Files.isRegularFile(sln)) {
            throw new IllegalArgumentException("Solution file not found: " + sln);
        }
        sln = sln.toRealPath(); // disk casing, so relative paths match the ones libclang reports
        Path solutionDir = sln.getParent();
        System.out.println("Solution: " + sln + "  (" + o.configuration() + "|" + o.platform() + ")");

        List<SolutionParser.ProjectInfo> projects = new SolutionParser(sln.toString()).parseProjects();
        MSBuildRunner msbuild = new MSBuildRunner();
        o.properties().forEach(msbuild::addProperty);
        System.out.println("MSBuild:  " + msbuild.getMsbuildExe());
        System.out.println("Projects: " + projects.size());

        String solutionName = stripExtension(sln.getFileName().toString());

        // Pass 1: evaluate every project (references need the referenced project's ConfigurationType).
        Map<String, MSBuildRunner.ProjectData> evaluated = new LinkedHashMap<>();
        Map<String, SolutionParser.ProjectInfo> byPath = new LinkedHashMap<>();
        for (SolutionParser.ProjectInfo proj : projects) {
            evaluated.put(proj.name, msbuild.evaluate(Path.of(proj.absolutePath), o.configuration(), o.platform(), solutionDir));
            byPath.put(pathKey(proj.absolutePath), proj);
        }

        // Pass 2: files, args and dependencies per project.
        Map<String, SourceFile> files = new LinkedHashMap<>();
        List<FactsBuilder.ModuleInput> moduleInputs = new ArrayList<>();
        for (SolutionParser.ProjectInfo proj : projects) {
            MSBuildRunner.ProjectData data = evaluated.get(proj.name);
            Path vcxproj = Path.of(proj.absolutePath);
            System.out.println("  " + proj.name + " (" + data.configurationType + "): "
                    + data.compileItems.size() + " files");

            Set<String> dropped = new LinkedHashSet<>();
            List<String> compileFiles = new ArrayList<>();
            for (MSBuildRunner.CompileItem item : data.compileItems) {
                dropped.addAll(item.droppedIncludeDirs);
                compileFiles.add(item.fullPath);
                String key = relKey(solutionDir, item.fullPath);
                files.putIfAbsent(key, new SourceFile(key, proj.name, item.fullPath, item.clangArgs));
                if (o.printArgs()) {
                    System.out.println("    " + key);
                    item.clangArgs.forEach(a -> System.out.println("        " + a));
                }
            }
            dropped.forEach(d -> System.out.println("    include dir not found, skipped: " + d));

            List<DependencyCollector.ProjectRef> refs = new ArrayList<>();
            for (String refPath : data.projectReferences) {
                SolutionParser.ProjectInfo target = byPath.get(pathKey(refPath));
                if (target != null) {
                    refs.add(new DependencyCollector.ProjectRef(target.name, evaluated.get(target.name).configurationType));
                } else {
                    System.out.println("    project reference not in the solution, skipped: " + refPath);
                }
            }
            List<String> linkLibs = DependencyCollector.linkLibraries(vcxproj, o.configuration(), o.platform());
            List<String> pragmaLibs = DependencyCollector.pragmaLibraries(compileFiles, vcxproj.getParent());
            List<String> libraries = new ArrayList<>(linkLibs);
            libraries.addAll(pragmaLibs);
            List<Dependency> deps = DependencyCollector.build(solutionName, refs, libraries,
                    DependencyCollector.vcpkgVersions(vcxproj.getParent(), solutionDir));
            moduleInputs.add(new FactsBuilder.ModuleInput(proj.name, folderOf(proj.relativePath),
                    proj.relativePath, data.configurationType, data.compileItems.size(), deps));

            System.out.println("    dependencies: " + (deps.isEmpty() ? "none" : deps.size()));
            deps.forEach(d -> System.out.println("      " + d));
            if (!linkLibs.isEmpty()) {
                System.out.println("      (from <Link>: " + String.join(", ", linkLibs) + ")");
            }
            if (!pragmaLibs.isEmpty()) {
                System.out.println("      (from #pragma comment(lib): " + String.join(", ", pragmaLibs) + ")");
            }
        }

        Map<String, String> projectFolders = new LinkedHashMap<>();
        for (SolutionParser.ProjectInfo proj : projects) {
            projectFolders.put(proj.name, folderOf(proj.relativePath));
        }
        long msbuildNanos = System.nanoTime() - startNanos;
        Extractor extractor = new Extractor(solutionDir, files.values(), projectFolders);
        long parseStart = System.nanoTime();
        List<T3Runner.FileResult> results = new T3Runner().run(files.values(), extractor::visit);
        long parseNanos = System.nanoTime() - parseStart;
        extractor.finish();
        T4Runner.report(extractor, projects.stream().map(p -> p.name).toList(), o.details());
        T5Runner.report(extractor, o.showCalls());

        String generatedAt = Instant.now().toString();
        long scanSeconds = Math.round((System.nanoTime() - startNanos) / 1e9);
        Facts facts = FactsBuilder.build(solutionName, solutionDir, moduleInputs, files.values(), results,
                extractor, generatedAt, scanSeconds);
        System.out.println("\n=== T7: DATABASE AND WINSOCK ===");
        System.out.println("Data sources: " + facts.database().dataSources().size()
                + ", tables: " + facts.database().tables().size()
                + ", SQL statements: " + extractor.sqliteRule.statements.size()
                + " (" + extractor.sqliteRule.statements.stream().filter(s -> s.sql() == null).count() + " dynamic)"
                + ", migration scripts: " + facts.database().migrationScripts().size()
                + ", ipc entries: " + facts.integrations().ipc().size());

        System.out.println("\n=== TIMING ===");
        System.out.printf("MSBuild evaluation (%d projects): %.1f s%n", projects.size(), msbuildNanos / 1e9);
        System.out.printf("Parse + extract (%d files):       %.1f s, %.0f ms per file on average%n",
                results.size(), parseNanos / 1e9, results.isEmpty() ? 0.0 : parseNanos / 1e6 / results.size());
        System.out.printf("Scan total (before output):      %.1f s%n", (System.nanoTime() - startNanos) / 1e9);
        return new ScanResult(facts, extractor);
    }

    static String pathKey(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    /** "src/Core/Core.vcxproj" -> "src/Core". */
    static String folderOf(String relativeProjectPath) {
        int slash = relativeProjectPath.lastIndexOf('/');
        return slash < 0 ? "" : relativeProjectPath.substring(0, slash);
    }

    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    static String relKey(Path solutionDir, String fullPath) {
        Path p = Path.of(fullPath).normalize();
        String rel = p.startsWith(solutionDir) ? solutionDir.relativize(p).toString() : p.toString();
        return rel.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
