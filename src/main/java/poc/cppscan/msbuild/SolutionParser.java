package poc.cppscan.msbuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SolutionParser {
    private final String solutionPath;

    public SolutionParser(String solutionPath) {
        this.solutionPath = solutionPath;
    }

    public List<ProjectInfo> parseProjects() throws IOException {
        List<ProjectInfo> projects = new ArrayList<>();
        Path slnPath = Paths.get(solutionPath);
        Path solutionDir = slnPath.getParent();

        String content = Files.readString(slnPath);
        // Match: Project("{...}") = "name", "path\to\project.vcxproj", "{...}"
        Pattern pattern = Pattern.compile("Project\\(\"\\{[^}]+\\}\"\\)\\s*=\\s*\"([^\"]+)\",\\s*\"([^\"]+)\",\\s*\"\\{[^}]+\\}\"");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String projectName = matcher.group(1);
            String projectPath = matcher.group(2);

            // Skip solution folders
            if (projectPath.endsWith(".vcxproj")) {
                Path fullPath = solutionDir.resolve(projectPath).toAbsolutePath();
                projects.add(new ProjectInfo(projectName, projectPath, fullPath.toString()));
            }
        }

        return projects;
    }

    public static class ProjectInfo {
        public final String name;
        public final String relativePath;
        public final String absolutePath;

        public ProjectInfo(String name, String relativePath, String absolutePath) {
            this.name = name;
            this.relativePath = relativePath.replace("\\", "/");
            this.absolutePath = absolutePath;
        }
    }
}
