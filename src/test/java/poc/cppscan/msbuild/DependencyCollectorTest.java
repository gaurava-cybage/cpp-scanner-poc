package poc.cppscan.msbuild;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poc.cppscan.model.Dependency;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DependencyCollectorTest {

    private static final String VCXPROJ = """
            <?xml version="1.0" encoding="utf-8"?>
            <Project DefaultTargets="Build" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <ItemDefinitionGroup>
                <Link><AdditionalDependencies>sqlite3.lib;ws2_32.lib;%(AdditionalDependencies)</AdditionalDependencies></Link>
              </ItemDefinitionGroup>
              <ItemDefinitionGroup Condition="'$(Configuration)|$(Platform)'=='Release|x64'">
                <Link><AdditionalDependencies>..\\lib\\zlib.lib;$(OTHER_LIB)</AdditionalDependencies></Link>
              </ItemDefinitionGroup>
              <ItemDefinitionGroup Condition="'$(Configuration)|$(Platform)'=='Debug|x64'">
                <Link><AdditionalDependencies>zlibd.lib</AdditionalDependencies></Link>
              </ItemDefinitionGroup>
            </Project>
            """;

    @Test
    void readsLinkLibrariesForSelectedConfigurationOnly(@TempDir Path dir) throws Exception {
        Path proj = dir.resolve("Lib.vcxproj");
        Files.writeString(proj, VCXPROJ);

        assertEquals(List.of("sqlite3.lib", "ws2_32.lib", "..\\lib\\zlib.lib"),
                DependencyCollector.linkLibraries(proj, "Release", "x64"));
        assertEquals(List.of("sqlite3.lib", "ws2_32.lib", "zlibd.lib"),
                DependencyCollector.linkLibraries(proj, "Debug", "x64"));
    }

    @Test
    void findsPragmaCommentLibInSourcesAndHeaders(@TempDir Path dir) throws Exception {
        Path cpp = dir.resolve("Net.cpp");
        Files.writeString(cpp, "#include \"Net.h\"\n  #pragma comment(lib, \"ws2_32.lib\")\nint x;\n");
        Files.createDirectories(dir.resolve("inc"));
        Files.writeString(dir.resolve("inc/Net.h"), "#pragma once\n# pragma comment( lib , \"Crypt32\" )\n");
        Files.writeString(dir.resolve("notes.txt"), "#pragma comment(lib, \"ignored.lib\")\n");

        assertEquals(List.of("ws2_32.lib", "Crypt32"),
                DependencyCollector.pragmaLibraries(List.of(cpp.toString()), dir));
    }

    @Test
    void buildsSortedDedupedListWithGroupsAndVcpkgVersions(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("vcpkg.json"), """
                { "name": "app",
                  "dependencies": [ "fmt", { "name": "sqlite3", "version>=": "3.45.0" } ],
                  "overrides": [ { "name": "zlib", "version": "1.3.1" } ] }
                """);
        Map<String, String> versions = DependencyCollector.vcpkgVersions(dir, dir);

        List<String> libs = new ArrayList<>(List.of("sqlite3.lib", "ws2_32.lib", "..\\lib\\zlib.lib"));
        libs.add("WS2_32.LIB"); // same library from a #pragma, different case
        List<Dependency> deps = DependencyCollector.build("Sol",
                List.of(new DependencyCollector.ProjectRef("Storage", "StaticLibrary"),
                        new DependencyCollector.ProjectRef("Core", "DynamicLibrary")),
                libs, versions);

        assertEquals(List.of(
                new Dependency("Sol", "Core", "unspecified", "compile", "SHARED", true),
                new Dependency("Sol", "Storage", "unspecified", "compile", "STATIC", true),
                new Dependency("msbuild-link", "sqlite3", "3.45.0", "compile", "IMPORT_LIB", null),
                new Dependency("windows-sdk", "ws2_32", "unspecified", "compile", "IMPORT_LIB", null),
                new Dependency("msbuild-link", "zlib", "1.3.1", "compile", "IMPORT_LIB", null)), deps);
    }
}
