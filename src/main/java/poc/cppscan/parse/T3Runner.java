package poc.cppscan.parse;

import org.bytedeco.llvm.clang.CXTranslationUnit;
import poc.cppscan.msbuild.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

public class T3Runner {

    public record FileResult(SourceFile file, ClangParser.Result result, long millis) {}

    /** Parses every file; {@code visitor} (may be null) sees each translation unit before it is disposed. */
    public List<FileResult> run(Collection<SourceFile> files, BiConsumer<SourceFile, CXTranslationUnit> visitor) {
        ClangParser parser = new ClangParser();
        List<FileResult> results = new ArrayList<>();
        long start = System.nanoTime();
        for (SourceFile f : files) {
            long t0 = System.nanoTime();
            ClangParser.Result r = parser.parseFile(f.fullPath(), f.clangArgs(),
                    visitor == null ? null : tu -> visitor.accept(f, tu));
            results.add(new FileResult(f, r, (System.nanoTime() - t0) / 1_000_000));
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        report(results, totalMs);
        return results;
    }

    private void report(List<FileResult> results, long totalMs) {
        long clean = results.stream().filter(r -> r.result.isClean()).count();
        System.out.println("\n=== T3: PARSE WHOLE SOLUTION ===");
        System.out.println("Files total:       " + results.size());
        System.out.println("Files clean:       " + clean);
        System.out.println("Files with errors: " + (results.size() - clean));
        System.out.printf("Clean rate:        %.1f%%%n", results.isEmpty() ? 0.0 : 100.0 * clean / results.size());
        System.out.printf("Total time:        %d ms%n", totalMs);

        System.out.println("\n5 slowest files:");
        results.stream().sorted(Comparator.comparingLong((FileResult r) -> r.millis).reversed()).limit(5)
                .forEach(r -> System.out.printf("  %6d ms  %s%n", r.millis, r.file.relPath()));

        Map<String, Integer> messageCounts = new TreeMap<>();
        System.out.println("\nFiles with errors:");
        for (FileResult r : results) {
            if (r.result.isClean()) {
                continue;
            }
            List<ClangParser.Diagnostic> errors = r.result.diagnostics.stream().filter(ClangParser.Diagnostic::isError).toList();
            if (r.result.errorCode != 0) {
                System.out.printf("  %s (%s): libclang error code %d%n", r.file.relPath(), r.file.module(), r.result.errorCode);
                continue;
            }
            System.out.printf("  %s (%s): %d errors%n", r.file.relPath(), r.file.module(), errors.size());
            errors.stream().limit(3).forEach(d -> System.out.printf("      %s:%d %s%n", fileName(d.file), d.line, d.message));
            errors.forEach(d -> messageCounts.merge(d.message, 1, Integer::sum));
        }

        if (!messageCounts.isEmpty()) {
            System.out.println("\nMost common errors:");
            messageCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(10)
                    .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(), e.getKey()));
        }
    }

    private static String fileName(String path) {
        return path.isEmpty() ? "?" : Path.of(path).getFileName().toString();
    }
}
