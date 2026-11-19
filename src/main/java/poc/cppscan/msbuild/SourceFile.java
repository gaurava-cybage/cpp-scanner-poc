package poc.cppscan.msbuild;

import java.util.List;

/** relPath: relative to the solution folder, forward slashes, lower case (the lookup key). */
public record SourceFile(String relPath, String module, String fullPath, List<String> clangArgs) {
}
