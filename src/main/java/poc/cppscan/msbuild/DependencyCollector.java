package poc.cppscan.msbuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import poc.cppscan.model.Dependency;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Builds modules[].dependencies: project references, link libraries, #pragma comment(lib), vcpkg versions. */
public class DependencyCollector {

    /** A referenced project, already resolved to its name and ConfigurationType. */
    public record ProjectRef(String name, String configurationType) {}

    static final Set<String> WINDOWS_SDK_LIBS = Set.of(
            "kernel32", "user32", "gdi32", "advapi32", "shell32", "ole32", "oleaut32", "uuid", "ws2_32",
            "winhttp", "wininet", "comdlg32", "winspool", "odbc32", "odbccp32", "shlwapi", "version",
            "crypt32", "bcrypt");

    private static final Pattern PRAGMA_LIB = Pattern.compile(
            "^[ \\t]*#[ \\t]*pragma[ \\t]+comment[ \\t]*\\([ \\t]*lib[ \\t]*,[ \\t]*\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern CONDITION_VALUE = Pattern.compile("==\\s*'([^']*)'");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Order: project references first, then libraries; alphabetical (case-insensitive) within each group.
     * Libraries are de-duplicated case-insensitively.
     */
    public static List<Dependency> build(String solutionName, List<ProjectRef> refs, Collection<String> libraries,
                                         Map<String, String> vcpkgVersions) {
        List<Dependency> result = new ArrayList<>();
        refs.stream()
                .sorted(Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT)))
                .forEach(r -> result.add(new Dependency(solutionName, r.name(), "unspecified", "compile",
                        linkage(r.configurationType()), true)));

        Map<String, String> libs = new TreeMap<>();
        for (String lib : libraries) {
            String name = libName(lib);
            if (!name.isEmpty()) {
                libs.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        for (Map.Entry<String, String> e : libs.entrySet()) {
            String group = WINDOWS_SDK_LIBS.contains(e.getKey()) ? "windows-sdk" : "msbuild-link";
            String version = vcpkgVersions.getOrDefault(e.getKey(), "unspecified");
            result.add(new Dependency(group, e.getValue(), version, "compile", "IMPORT_LIB", null));
        }
        return result;
    }

    static String linkage(String configurationType) {
        return switch (configurationType) {
            case "StaticLibrary" -> "STATIC";
            case "DynamicLibrary" -> "SHARED";
            case "Application" -> "EXECUTABLE";
            default -> "UNKNOWN";
        };
    }

    /** "..\lib\sqlite3.lib" -> "sqlite3". */
    static String libName(String raw) {
        String name = raw.trim().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".lib")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /**
     * AdditionalDependencies from the &lt;Link&gt; settings in the .vcxproj itself, for groups with no condition or a
     * condition matching the chosen configuration. Imported .props files are not read.
     */
    public static List<String> linkLibraries(Path vcxproj, String configuration, String platform) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            doc = f.newDocumentBuilder().parse(vcxproj.toFile());
        } catch (Exception e) {
            throw new IOException("Cannot read " + vcxproj + ": " + e.getMessage(), e);
        }
        Set<String> libs = new LinkedHashSet<>();
        NodeList groups = doc.getElementsByTagName("ItemDefinitionGroup");
        for (int i = 0; i < groups.getLength(); i++) {
            Element group = (Element) groups.item(i);
            if (!conditionMatches(group, configuration, platform)) {
                continue;
            }
            for (Element link : children(group, "Link")) {
                if (!conditionMatches(link, configuration, platform)) {
                    continue;
                }
                for (Element deps : children(link, "AdditionalDependencies")) {
                    if (conditionMatches(deps, configuration, platform)) {
                        for (String part : deps.getTextContent().split(";")) {
                            String v = part.trim();
                            if (!v.isEmpty() && !v.contains("%(") && !v.contains("$(")) {
                                libs.add(v);
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(libs);
    }

    /** No condition, or "'...'=='Release|x64'" / "'...'=='Release'" matching the chosen configuration. */
    static boolean conditionMatches(Element e, String configuration, String platform) {
        String cond = e.getAttribute("Condition").trim();
        if (cond.isEmpty()) {
            return true;
        }
        Matcher m = CONDITION_VALUE.matcher(cond);
        if (!m.find()) {
            return false;
        }
        String value = m.group(1);
        return value.equalsIgnoreCase(configuration + "|" + platform) || value.equalsIgnoreCase(configuration);
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && el.getTagName().equals(tag)) {
                result.add(el);
            }
        }
        return result;
    }

    /**
     * Library names from #pragma comment(lib, "...") in the project's compiled files and in headers under the project
     * folder. Text match only: a pragma inside #if 0 or a block comment is still counted.
     */
    public static List<String> pragmaLibraries(Collection<String> compileFiles, Path projectDir) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        compileFiles.forEach(f -> files.add(Path.of(f)));
        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".h") || n.endsWith(".hpp");
                    })
                    .sorted()
                    .forEach(files::add);
        }
        Set<String> libs = new LinkedHashSet<>();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            // ISO-8859-1 never fails on bytes; the library name itself is ASCII.
            Matcher m = PRAGMA_LIB.matcher(Files.readString(file, StandardCharsets.ISO_8859_1));
            while (m.find()) {
                libs.add(m.group(1));
            }
        }
        return new ArrayList<>(libs);
    }

    /**
     * Package versions from vcpkg.json in the project folder, then the solution folder (first one found wins per
     * package). "overrides" beat "version>=" constraints. Keys are lower case.
     */
    public static Map<String, String> vcpkgVersions(Path projectDir, Path solutionDir) throws IOException {
        Map<String, String> versions = new LinkedHashMap<>();
        for (Path dir : List.of(projectDir, solutionDir)) {
            Path manifest = dir.resolve("vcpkg.json");
            if (!Files.isRegularFile(manifest)) {
                continue;
            }
            JsonNode root = JSON.readTree(manifest.toFile());
            Map<String, String> here = new LinkedHashMap<>();
            for (JsonNode dep : root.path("dependencies")) {
                if (dep.isObject() && dep.hasNonNull("version>=")) {
                    here.put(dep.path("name").asText().toLowerCase(Locale.ROOT), dep.path("version>=").asText());
                }
            }
            for (JsonNode o : root.path("overrides")) {
                if (o.hasNonNull("version")) {
                    here.put(o.path("name").asText().toLowerCase(Locale.ROOT), o.path("version").asText());
                }
            }
            here.forEach(versions::putIfAbsent);
        }
        return versions;
    }
}
