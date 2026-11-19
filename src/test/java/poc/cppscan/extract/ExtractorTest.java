package poc.cppscan.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poc.cppscan.msbuild.SourceFile;
import poc.cppscan.parse.ClangParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExtractorTest {

    private static final String HEADER = """
            #pragma once
            #include <memory>
            #include <string>
            #include <thread>
            namespace demo { namespace core {
            class Logger;
            struct Point { int x; int y; };
            class IStore {
            public:
                virtual ~IStore() = default;
                virtual void save(const std::string& key) = 0;
                virtual int count() const = 0;
            };
            class Store final : public IStore, protected virtual Point {
            public:
                void save(const std::string& key) override;
                int count() const override { return n_; }
                static Store* create(char** argv, int n);
                class Cursor { public: void next(); };
            private:
                std::unique_ptr<Logger> logger_;
                const IStore* other_;
                std::thread worker_;
                std::string name_;
                int n_ = 0;
            };
            } }
            """;

    private static final String SOURCE = """
            #include "Store.h"
            namespace demo { namespace core {
            void Store::save(const std::string& key) { (void)key; }
            Store* Store::create(char** argv, int n) { (void)argv; (void)n; return nullptr; }
            void Store::Cursor::next() {}
            } }
            int main(int argc, char** argv) { (void)argc; (void)argv; return 0; }
            """;

    @Test
    void extractsClassesFunctionsAndIds(@TempDir Path root) throws Exception {
        Path lib = Files.createDirectories(root.resolve("src/Lib"));
        Files.writeString(lib.resolve("Store.h"), HEADER);
        Path cpp = lib.resolve("Store.cpp");
        Files.writeString(cpp, SOURCE);
        Path solutionDir = root.toRealPath();

        SourceFile sf = new SourceFile("src/lib/store.cpp", "Lib", cpp.toString(),
                List.of("--driver-mode=cl", "-fms-compatibility", "-fms-extensions", "/std:c++17", "/EHsc"));
        Extractor ex = new Extractor(solutionDir, List.of(sf), Map.of("Lib", "src/Lib"));
        ClangParser.Result r = new ClangParser().parseFile(sf.fullPath(), sf.clangArgs(), tu -> ex.visit(sf, tu));
        ex.finish();
        assertTrue(r.isClean(), () -> "parse errors: " + r.diagnostics);

        Map<String, ClassInfo> byName = new java.util.TreeMap<>();
        ex.classes().values().forEach(c -> byName.put(c.qualifiedName(), c));
        assertEquals(List.of("demo::core::IStore", "demo::core::Point", "demo::core::Store",
                "demo::core::Store::Cursor"), List.copyOf(byName.keySet()), "forward-declared Logger must not appear");

        ClassInfo store = byName.get("demo::core::Store");
        assertEquals("demo::core", store.packageName);
        assertEquals("src/Lib/Store.h", store.filePath);
        assertEquals("COMPONENT", store.type);
        assertEquals(List.of("final"), store.annotations);
        assertEquals(List.of(new ClassInfo.BaseClass("IStore", "public", false),
                new ClassInfo.BaseClass("Point", "protected", true)), store.baseClasses);
        assertEquals(List.of("IStore", "Logger", "std::thread"), List.copyOf(store.dependencyTypes));
        assertEquals(List.of("src/Lib/Store.cpp"), List.copyOf(store.sourceFiles));
        assertEquals("Lib", store.module);

        assertEquals("INTERFACE", byName.get("demo::core::IStore").type);
        assertEquals(List.of("abstract"), byName.get("demo::core::IStore").annotations);
        assertEquals("ENTITY", byName.get("demo::core::Point").type);
        assertEquals(List.of("struct"), byName.get("demo::core::Point").annotations);
        assertEquals("Store::Cursor", byName.get("demo::core::Store::Cursor").className);

        List<String> ids = ex.functions().values().stream().map(FunctionInfo::id).sorted().toList();
        assertEquals(List.of(
                "#main(int, char**)", // "~IStore() = default" is not a definition for libclang: no body, no entry

                "demo::core::Store#count()",
                "demo::core::Store#create(char**, int)",
                "demo::core::Store#save(const std::string&)",
                "demo::core::Store::Cursor#next()"), ids);
        FunctionInfo count = ex.functions().values().stream().filter(f -> f.name.equals("count")).findFirst().orElseThrow();
        assertTrue(count.isConst && count.isVirtual);
    }

    @Test
    void cleansTypeSpellings() {
        assertEquals("std::map<A, B>", Extractor.cleanType("const std::map<A,B> &"));
        assertEquals("Foo", Extractor.firstTemplateArgument("std::unique_ptr<Foo, std::default_delete<Foo>>"));
        assertEquals("const std::string&", FunctionInfo.paramSpelling("const std::string &"));
    }
}
