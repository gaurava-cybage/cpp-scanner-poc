package poc.cppscan.msbuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MSBuildRunner {

    public static class CompileItem {
        public final String fullPath;
        public final List<String> clangArgs;
        public final List<String> droppedIncludeDirs;

        CompileItem(String fullPath, List<String> clangArgs, List<String> droppedIncludeDirs) {
            this.fullPath = fullPath;
            this.clangArgs = clangArgs;
            this.droppedIncludeDirs = droppedIncludeDirs;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String msbuildExe;

    public MSBuildRunner() throws IOException, InterruptedException {
        this.msbuildExe = findMSBuild();
    }

    /** Extra "NAME=VALUE" global properties (--property), e.g. an SDK folder the project files expect. */
    private final List<String> properties = new ArrayList<>();

    public String getMsbuildExe() {
        return msbuildExe;
    }

    public void addProperty(String nameEqualsValue) {
        properties.add(nameEqualsValue);
    }

    /** What one msbuild evaluation of a .vcxproj gives us. */
    public static class ProjectData {
        public final String configurationType;
        public final List<CompileItem> compileItems;
        /** Absolute paths of referenced .vcxproj files, in msbuild order. */
        public final List<String> projectReferences;

        ProjectData(String configurationType, List<CompileItem> compileItems, List<String> projectReferences) {
            this.configurationType = configurationType;
            this.compileItems = compileItems;
            this.projectReferences = projectReferences;
        }
    }

    public ProjectData evaluate(Path vcxproj, String configuration, String platform, Path solutionDir)
            throws IOException, InterruptedException {
        // Trailing "/" instead of "\" so Windows argument quoting does not escape the closing quote.
        List<String> cmd = new ArrayList<>(List.of(msbuildExe, vcxproj.toString(),
                "-getItem:ClCompile", "-getItem:ProjectReference", "-getProperty:ConfigurationType",
                "-p:Configuration=" + configuration, "-p:Platform=" + platform,
                "-p:SolutionDir=" + solutionDir + "/", "-nologo"));
        properties.forEach(p -> cmd.add("-p:" + p));
        String out = run(cmd);
        int start = out.indexOf('{');
        if (start < 0) {
            throw new IOException("msbuild returned no JSON for " + vcxproj + ": " + out.strip());
        }
        return parseProjectData(out.substring(start), vcxproj.getParent(), platform);
    }

    static ProjectData parseProjectData(String json, Path projectDir, String platform) throws IOException {
        JsonNode root = JSON.readTree(json);
        String type = root.path("Properties").path("ConfigurationType").asText("");
        List<String> refs = new ArrayList<>();
        for (JsonNode ref : root.path("Items").path("ProjectReference")) {
            String full = ref.path("FullPath").asText("");
            if (full.isEmpty()) {
                full = projectDir.resolve(ref.path("Identity").asText()).normalize().toString();
            }
            refs.add(full);
        }
        return new ProjectData(type, parseCompileItems(json, projectDir, platform), refs);
    }

    static List<CompileItem> parseCompileItems(String json, Path projectDir, String platform) throws IOException {
        List<CompileItem> result = new ArrayList<>();
        for (JsonNode item : JSON.readTree(json).path("Items").path("ClCompile")) {
            String fullPath = item.path("FullPath").asText("");
            if (fullPath.isEmpty()) {
                fullPath = projectDir.resolve(item.path("Identity").asText()).normalize().toString();
            }
            if (!isCOrCpp(fullPath)) {
                continue;
            }
            List<String> dropped = new ArrayList<>();
            List<String> args = buildArgs(item, Path.of(fullPath), projectDir, platform, dropped);
            result.add(new CompileItem(fullPath, args, dropped));
        }
        return result;
    }

    static List<String> buildArgs(JsonNode item, Path sourceFile, Path projectDir, String platform, List<String> dropped) {
        List<String> args = new ArrayList<>(List.of("--driver-mode=cl", "-fms-compatibility", "-fms-extensions"));
        if (platform.equalsIgnoreCase("Win32") || platform.equalsIgnoreCase("x86")) {
            args.add("-m32");
        }
        for (String define : split(item, "PreprocessorDefinitions")) {
            args.add("/D" + define);
        }
        for (String inc : split(item, "AdditionalIncludeDirectories")) {
            Path dir = resolve(projectDir, inc);
            if (dir != null && Files.isDirectory(dir)) {
                args.add("/I" + dir);
            } else {
                dropped.add(inc);
            }
        }
        String std = languageStandard(item, sourceFile);
        if (std != null) {
            args.add(std);
        }
        switch (item.path("ExceptionHandling").asText("")) {
            case "Sync" -> args.add("/EHsc");
            case "Async" -> args.add("/EHa");
            case "SyncCThrow" -> args.add("/EHs");
            default -> { }
        }
        switch (item.path("RuntimeLibrary").asText("")) {
            case "MultiThreadedDLL" -> args.add("/MD");
            case "MultiThreadedDebugDLL" -> args.add("/MDd");
            case "MultiThreaded" -> args.add("/MT");
            case "MultiThreadedDebug" -> args.add("/MTd");
            default -> { }
        }
        if ("Use".equals(item.path("PrecompiledHeader").asText(""))) {
            String pch = item.path("PrecompiledHeaderFile").asText("").trim();
            if (!pch.isEmpty()) {
                args.add("/FI" + resolveFile(sourceFile.getParent(), projectDir, pch));
            }
        }
        for (String forced : split(item, "ForcedIncludeFiles")) {
            args.add("/FI" + resolveFile(sourceFile.getParent(), projectDir, forced));
        }
        return args;
    }

    private static Set<String> split(JsonNode item, String key) {
        Set<String> values = new LinkedHashSet<>();
        for (String part : item.path(key).asText("").split(";")) {
            String v = part.trim();
            if (!v.isEmpty() && !v.contains("%(") && !v.contains("$(")) {
                values.add(v);
            }
        }
        return values;
    }

    private static String languageStandard(JsonNode item, Path sourceFile) {
        String name = sourceFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".c")) {
            switch (item.path("LanguageStandard_C").asText("")) {
                case "stdc11": return "/std:c11";
                case "stdc17": return "/std:c17";
                default: return null;
            }
        }
        switch (item.path("LanguageStandard").asText("")) {
            case "stdcpp14": return "/std:c++14";
            case "stdcpp17": return "/std:c++17";
            case "stdcpp20": return "/std:c++20";
            case "stdcpplatest": return "/std:c++latest";
            default: return null;
        }
    }

    private static Path resolve(Path base, String raw) {
        try {
            return base.resolve(raw).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    // MSVC looks for a quoted forced include next to the source file first, then in the project folder.
    private static String resolveFile(Path sourceDir, Path projectDir, String raw) {
        for (Path base : List.of(sourceDir, projectDir)) {
            Path p = resolve(base, raw);
            if (p != null && Files.isRegularFile(p)) {
                return p.toString();
            }
        }
        return raw;
    }

    private static boolean isCOrCpp(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".c") || p.endsWith(".cpp") || p.endsWith(".cc") || p.endsWith(".cxx");
    }

    private static String run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), Charset.defaultCharset());
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException(cmd.get(0) + " exited with " + exit + ": " + out.strip());
        }
        return out;
    }

    // Developer prompt puts MSBuild on PATH; otherwise ask vswhere, which every VS 2017+ install ships.
    public static String findMSBuild() throws IOException, InterruptedException {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path candidate = resolve(Path.of(""), dir.trim());
            if (candidate != null && !dir.isBlank() && Files.isRegularFile(candidate.resolve("MSBuild.exe"))) {
                return candidate.resolve("MSBuild.exe").toString();
            }
        }
        String programFilesX86 = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
        Path vswhere = Path.of(programFilesX86, "Microsoft Visual Studio", "Installer", "vswhere.exe");
        if (Files.isRegularFile(vswhere)) {
            String out = run(List.of(vswhere.toString(), "-latest", "-products", "*",
                    "-requires", "Microsoft.Component.MSBuild", "-find", "MSBuild\\**\\Bin\\MSBuild.exe"));
            for (String line : out.split("\\R")) {
                if (!line.isBlank()) {
                    return line.trim();
                }
            }
        }
        throw new IOException("MSBuild.exe not found. Run from a Visual Studio Developer prompt.");
    }
}
