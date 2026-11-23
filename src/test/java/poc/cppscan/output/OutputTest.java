package poc.cppscan.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poc.cppscan.extract.Extractor;
import poc.cppscan.model.Dependency;
import poc.cppscan.model.Facts;
import poc.cppscan.msbuild.SourceFile;
import poc.cppscan.parse.ClangParser;
import poc.cppscan.parse.T3Runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        return keys;
    }

    private static Facts emptyFacts(Path root) throws Exception {
        Extractor ex = new Extractor(root, List.of(), Map.of());
        ex.finish();
        List<FactsBuilder.ModuleInput> modules = List.of(
                new FactsBuilder.ModuleInput("App", "src/App", "src/App/App.vcxproj", "Application", 0,
                        List.of(new Dependency("windows-sdk", "ws2_32", "unspecified", "compile", "IMPORT_LIB", null))));
        return FactsBuilder.build("Sol", root, modules, List.of(), List.<T3Runner.FileResult>of(), ex,
                "2026-11-18T00:00:00Z", 0);
    }

    @Test
    void topLevelKeysAreTheReferenceKeysPlusThePocOnes(@TempDir Path root) throws Exception {
        JsonNode reference = JSON.readTree(Path.of("reference", "tinypay-facts.json").toFile());
        List<String> expected = new ArrayList<>(keys(reference));
        // The POC adds these (docs/output-format.md, "Top level"): three after generatedAt, diagnostics last.
        expected.addAll(expected.indexOf("generatedAt") + 1, List.of("sourceLanguage", "schemaVersion", "languages"));
        expected.add("diagnostics");

        JsonNode ours = JSON.readTree(JsonWriter.toJson(emptyFacts(root)));
        assertEquals(expected, keys(ours));
        assertEquals(keys(reference.path("codeFlow")), keys(ours.path("codeFlow")));
        assertEquals(keys(reference.path("database")), keys(ours.path("database")));
        assertEquals("MONOLITH", ours.path("deploymentTopology").path("architectureStyle").asText());
        assertFalse(ours.path("aggregatedDependencies").get(0).has("internal"));
    }

    @Test
    void formattingMatchesTheReferenceStyle(@TempDir Path root) throws Exception {
        String json = JsonWriter.toJson(emptyFacts(root));
        String nl = System.lineSeparator();
        assertTrue(json.startsWith("{" + nl + "  \"targetProjectName\" : \"Sol\","), json.substring(0, 60));
        assertTrue(json.contains("\"modules\" : [ {" + nl + "    \"name\" : \"App\","));
        assertTrue(json.contains("\"deploymentDescriptors\" : [ ],"));
        assertFalse(json.endsWith(nl), "the reference file has no trailing newline");

        String reference = Files.readString(Path.of("reference", "tinypay-facts.json"));
        assertTrue(reference.contains("\"deploymentDescriptors\" : [ ],"), "same empty-array style as the reference");
        assertTrue(reference.contains("\"modules\" : [ {" + nl), "same array-of-objects style as the reference");
    }

    @Test
    void flowFollowsCallsSkipsLoggingAndAccessorsAndTruncatesAtMaxDepth(@TempDir Path root) throws Exception {
        Path cpp = Files.createDirectories(root.resolve("src")).resolve("app.cpp");
        Files.writeString(cpp, """
                struct Logger { static void Log(int); };
                struct Box { int v; int Get() const { return v; }
                  void c() {}
                  void b() { c(); }
                  void a() { Logger::Log(1); Get(); b(); } };
                int main() { Box box; box.a(); return 0; }
                """);
        SourceFile sf = new SourceFile("src/app.cpp", "App", cpp.toString(),
                List.of("--driver-mode=cl", "-fms-compatibility", "-fms-extensions", "/std:c++17"));
        Extractor ex = new Extractor(root.toRealPath(), List.of(sf), Map.of("App", "src"));
        new ClangParser().parseFile(sf.fullPath(), sf.clangArgs(), tu -> ex.visit(sf, tu));
        ex.finish();
        List<Facts.Method> methods = FactsBuilder.methods(ex);
        Facts.EntryPoint main = new Facts.EntryPoint("MAIN App", "MAIN", "App", "", "#main()");

        Facts.Flow full = new FlowBuilder(ex, methods, 6).build(main, "t");
        assertEquals(List.of("1 #main()->Box#a()", "2 Box#a()->Box#b()", "3 Box#b()->Box#c()"),
                full.steps().stream().map(s -> s.depth() + " " + s.from() + "->" + s.to()).toList());
        assertEquals(Map.of("std", 0, "logging", 1, "accessors", 1), full.omitted());
        assertFalse(full.truncated());
        assertEquals(List.of("App", "Box"), full.participants().stream().map(Facts.Participant::id).toList());
        assertEquals(List.of("#main()", "Box#a()", "Box#b()", "Box#c()"),
                full.methods().stream().map(Facts.Method::id).toList());

        Facts.Flow cut = new FlowBuilder(ex, methods, 2).build(main, "t");
        assertEquals(2, cut.steps().size());
        assertTrue(cut.truncated());
        assertEquals("MAIN-App.json", FlowBuilder.fileName("MAIN App"));
    }
}
