package poc.cppscan.output;

import org.junit.jupiter.api.Test;
import poc.cppscan.model.Facts;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewSampleTest {

    private static Facts.Method method(int i, int callCount) {
        List<Facts.Call> calls = new ArrayList<>();
        for (int s = 1; s <= callCount; s++) {
            calls.add(new Facts.Call("METHOD_CALL", "Target", "f" + s, List.of("ns#f" + s + "()"), "EXACT",
                    null, null, null, null, null, s,
                    List.of(new Facts.ControlStep("IF", "a | b", "then", 10 + s)), false, 10 + s));
        }
        return new Facts.Method("ns::C" + i + "#m()", "Lib", "C" + i, "COMPONENT", "m", List.of(), "void", false,
                "", null, calls, List.of(), "src/c" + i + ".cpp", 1);
    }

    private static Facts.Component component(int i) {
        return new Facts.Component("C" + i, "ns", "src/c" + i + ".h", List.of("src/c" + i + ".cpp"), "COMPONENT",
                List.of(), List.of(new Facts.BaseClass("Base", "public", false)), List.of());
    }

    @Test
    void sampleIsDeterministicAndCapped() {
        List<Facts.Method> methods = new ArrayList<>();
        List<Facts.Component> components = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            methods.add(method(i, 3));
            components.add(component(i));
        }
        String first = ReviewSample.markdown("App", methods, components);
        assertEquals(first, ReviewSample.markdown("App", methods, components));
        assertTrue(first.contains("20 of 45 calls and 10 of 15 classes"));
        assertEquals(20, first.lines().filter(l -> l.startsWith("| ") && l.contains("METHOD_CALL")).count());
        assertEquals(10, first.lines().filter(l -> l.startsWith("| ") && l.contains("`src/c")
                && l.contains("COMPONENT")).count());
        assertTrue(first.contains("`IF (a \\| b)` then"), "pipes in conditions are escaped");
    }

    @Test
    void fewerItemsThanRequestedGivesAll() {
        String md = ReviewSample.markdown("App", List.of(method(1, 2)), List.of(component(1)));
        assertTrue(md.contains("2 of 2 calls and 1 of 1 classes"));
        assertEquals(List.of(1, 2, 3), ReviewSample.sample(List.of(1, 2, 3), 5).stream().sorted().toList());
    }
}
