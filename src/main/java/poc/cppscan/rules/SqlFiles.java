package poc.cppscan.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** The .sql files in the solution folder (read-only), for database.tables and migrationScripts. */
public final class SqlFiles {

    private SqlFiles() {}

    /** Solution-relative paths with forward slashes, sorted. Hidden folders (".git", ".vs") are skipped. */
    public static List<String> find(Path solutionDir) throws IOException {
        try (Stream<Path> walk = Files.walk(solutionDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .filter(p -> !hidden(solutionDir.relativize(p)))
                    .map(p -> solutionDir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static boolean hidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    public static String read(Path solutionDir, String relativePath) throws IOException {
        // ISO-8859-1 never fails on bytes; table and column names are ASCII.
        byte[] bytes = Files.readAllBytes(solutionDir.resolve(relativePath));
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.indexOf('�') >= 0 ? new String(bytes, StandardCharsets.ISO_8859_1) : text;
    }

    /** 1-based line of a character offset. */
    public static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
