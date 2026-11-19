package poc.cppscan.extract;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poc.cppscan.msbuild.SourceFile;
import poc.cppscan.parse.ClangParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** T5 snippets: seq order, switch/case, else-if, try/catch, loops, lambdas. No STL, so it parses fast. */
class BodyWalkerTest {

    private static final String SOURCE = """
            int a(); int b(); int c(); int f(int); int g();
            namespace n { struct Base { virtual void run(); void plain(); }; struct Impl : Base { void run() override; };
            void Base::run() {} void Base::plain() {} void Impl::run() {} }

            void nested() { f(g()); }

            void cases(int x) {
                switch (x) {
                case 1:
                    a();
                    b();
                    break;
                case 2: case 3:
                    c();
                    break;
                default:
                    g();
                }
            }

            void elseIf(int x) {
                if (x > 0) a();
                else if (x < 0) b();
                else c();
            }

            void tries() {
                try { a(); }
                catch (int e) { b(); }
                catch (...) { c(); }
            }

            int loops(int x) {
                for (int i = 0; i < 3; i++) { a(); }
                while (x > 0) { x = b(); }
                do { c(); } while (x < 5);
                if (g()) { throw 1; }
                return f(x);
            }

            int lambdas() {
                auto l = [] { return a(); };
                l();
                return 0;
            }

            void virtuals(n::Base& base) { base.run(); base.Base::run(); base.plain(); }
            """;

    private static Extractor ex;

    @BeforeAll
    static void parse(@TempDir Path root) throws Exception {
        Path cpp = Files.createDirectories(root.resolve("src")).resolve("snippets.cpp");
        Files.writeString(cpp, SOURCE);
        SourceFile sf = new SourceFile("src/snippets.cpp", "Lib", cpp.toString(),
                List.of("--driver-mode=cl", "-fms-compatibility", "-fms-extensions", "/std:c++17", "/EHsc"));
        ex = new Extractor(root.toRealPath(), List.of(sf), Map.of("Lib", "src"));
        ClangParser.Result r = new ClangParser().parseFile(sf.fullPath(), sf.clangArgs(), tu -> ex.visit(sf, tu));
        ex.finish();
        assertTrue(r.isClean(), () -> "parse errors: " + r.diagnostics);
    }

    private static FunctionInfo fn(String name) {
        return ex.functions().values().stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
    }

    private static String describe(CallInfo c) {
        return c.seq + " " + c.targetMethod + " " + c.controlPath.stream()
                .map(s -> s.kind() + "(" + s.condition() + ")" + s.branch()).toList();
    }

    @Test
    void innerCallGetsLowerSeq() {
        assertEquals(List.of("1 g []", "2 f []"), fn("nested").calls.stream().map(BodyWalkerTest::describe).toList());
    }

    @Test
    void caseLabelAppliesToFollowingSiblings() {
        assertEquals(List.of(
                "1 a [SWITCH(x)1]",
                "2 b [SWITCH(x)1]",
                "3 c [SWITCH(x)2, 3]",
                "4 g [SWITCH(x)default]"), fn("cases").calls.stream().map(BodyWalkerTest::describe).toList());
    }

    @Test
    void elseIfPath() {
        assertEquals(List.of(
                "1 a [IF(x > 0)then]",
                "2 b [IF(x > 0)else, IF(x < 0)then]",
                "3 c [IF(x > 0)else, IF(x < 0)else]"), fn("elseIf").calls.stream().map(BodyWalkerTest::describe).toList());
    }

    @Test
    void tryAndCatch() {
        assertEquals(List.of(
                "1 a [TRY()try]",
                "2 b [CATCH(int e)catch]",
                "3 c [CATCH(...)catch]"), fn("tries").calls.stream().map(BodyWalkerTest::describe).toList());
    }

    @Test
    void loopsConditionsAndExits() {
        FunctionInfo f = fn("loops");
        assertEquals(List.of(
                "1 a [FOR(int i = 0; i < 3; i++)body]",
                "2 b [WHILE(x > 0)body]",
                "3 c [DO(x < 5)body]",
                "4 g []",
                "5 f []"), f.calls.stream().map(BodyWalkerTest::describe).toList());
        assertEquals(2, f.exits.size());
        assertEquals("THROW", f.exits.get(0).kind());
        assertEquals("IF", f.exits.get(0).controlPath().get(0).kind());
        assertEquals("RETURN", f.exits.get(1).kind());
        assertTrue(f.exits.get(1).controlPath().isEmpty());
    }

    @Test
    void returnInsideLambdaIsNotAnExit() {
        FunctionInfo f = fn("lambdas");
        assertEquals(1, f.exits.size(), "only the function's own return");
        assertTrue(f.calls.stream().anyMatch(c -> c.targetMethod.equals("a")), "calls inside the lambda are kept");
        assertEquals(List.of("1 a []"), f.calls.stream().map(BodyWalkerTest::describe).toList(),
                "calling the lambda itself (l()) is not a call");
    }

    @Test
    void stdThreadConstructorIsAsync(@TempDir Path root) throws Exception {
        Path cpp = Files.createDirectories(root.resolve("src")).resolve("thread.cpp");
        Files.writeString(cpp, """
                #include <thread>
                struct Server { void handle(int); void run() { std::thread(&Server::handle, this, 1).detach(); handle(2); } };
                """);
        SourceFile sf = new SourceFile("src/thread.cpp", "Lib", cpp.toString(),
                List.of("--driver-mode=cl", "-fms-compatibility", "-fms-extensions", "/std:c++17", "/EHsc"));
        Extractor tex = new Extractor(root.toRealPath(), List.of(sf), Map.of("Lib", "src"));
        new ClangParser().parseFile(sf.fullPath(), sf.clangArgs(), tu -> tex.visit(sf, tu));
        FunctionInfo run = tex.functions().values().stream().filter(f -> f.name.equals("run")).findFirst().orElseThrow();
        assertEquals(List.of("1 thread []", "2 handle []"), run.calls.stream().map(BodyWalkerTest::describe).toList());
        assertTrue(run.calls.get(0).async);
        assertEquals("std::thread", run.calls.get(0).targetClass);
        assertFalse(run.calls.get(1).async);
    }

    @Test
    void virtualAndQualifiedCalls() {
        List<CallInfo> calls = fn("virtuals").calls;
        assertEquals("VIRTUAL", calls.get(0).resolution);
        assertEquals(List.of("n::Base#run()", "n::Impl#run()"), calls.get(0).targetMethodIds);
        assertEquals("EXACT", calls.get(1).resolution, "Base::run() is a qualified call");
        assertEquals("EXACT", calls.get(2).resolution);
        assertEquals("n::Base", calls.get(2).targetClass);
    }
}
