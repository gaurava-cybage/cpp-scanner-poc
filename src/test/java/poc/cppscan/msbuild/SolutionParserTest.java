package poc.cppscan.msbuild;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class SolutionParserTest {

    @Test
    public void testParsePayHubSolution() throws Exception {
        SolutionParser parser = new SolutionParser("D:\\SampleCppCode\\PayHub\\PayHub.sln");
        List<SolutionParser.ProjectInfo> projects = parser.parseProjects();

        assertEquals(6, projects.size());

        assertEquals("Core", projects.get(0).name);
        assertEquals("Storage", projects.get(1).name);
        assertEquals("Network", projects.get(2).name);
        assertEquals("Messaging", projects.get(3).name);
        assertEquals("Integration", projects.get(4).name);
        assertEquals("App", projects.get(5).name);

        // Verify relative paths are normalized to forward slashes
        for (SolutionParser.ProjectInfo proj : projects) {
            assertFalse(proj.relativePath.contains("\\"), "Path should use forward slashes: " + proj.relativePath);
        }
    }
}
