package poc.cppscan.msbuild;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MSBuildRunnerTest {

    @Test
    void buildsClangArgsFromGetItemJson(@TempDir Path root) throws Exception {
        Path project = Files.createDirectories(root.resolve("src/Lib"));
        Path shared = Files.createDirectories(root.resolve("src/Shared"));
        Files.writeString(project.resolve("pch.h"), "#pragma once\n");
        String src = project.resolve("Foo.cpp").toString().replace("\\", "\\\\");

        String json = """
            { "Items": { "ClCompile": [
              { "Identity": "Foo.cpp", "FullPath": "%s",
                "AdditionalIncludeDirectories": "..\\\\Shared;\\\\include;%%(AdditionalIncludeDirectories)",
                "PreprocessorDefinitions": "_UNICODE;UNICODE;NDEBUG;;",
                "LanguageStandard": "stdcpp17",
                "ExceptionHandling": "Sync", "RuntimeLibrary": "MultiThreadedDLL",
                "PrecompiledHeader": "Use", "PrecompiledHeaderFile": "pch.h",
                "ForcedIncludeFiles": "" },
              { "Identity": "notes.txt", "FullPath": "x.txt" }
            ] } }
            """.formatted(src);

        List<MSBuildRunner.CompileItem> items = MSBuildRunner.parseCompileItems(json, project, "x64");

        assertEquals(1, items.size());
        MSBuildRunner.CompileItem item = items.get(0);
        assertEquals(project.resolve("Foo.cpp").toString(), item.fullPath);
        assertEquals(List.of(
                "--driver-mode=cl", "-fms-compatibility", "-fms-extensions",
                "/D_UNICODE", "/DUNICODE", "/DNDEBUG",
                "/I" + shared,
                "/std:c++17", "/EHsc", "/MD",
                "/FI" + project.resolve("pch.h")), item.clangArgs);
        assertEquals(List.of("\\include"), item.droppedIncludeDirs);
    }

    @Test
    void win32AddsM32AndCreatePchIsNotForced(@TempDir Path project) throws Exception {
        String src = project.resolve("pch.cpp").toString().replace("\\", "\\\\");
        String json = """
            { "Items": { "ClCompile": [
              { "FullPath": "%s", "PrecompiledHeader": "Create", "PrecompiledHeaderFile": "pch.h",
                "LanguageStandard": "Default" } ] } }
            """.formatted(src);

        List<String> args = MSBuildRunner.parseCompileItems(json, project, "Win32").get(0).clangArgs;

        assertEquals(List.of("--driver-mode=cl", "-fms-compatibility", "-fms-extensions", "-m32"), args);
    }
}
