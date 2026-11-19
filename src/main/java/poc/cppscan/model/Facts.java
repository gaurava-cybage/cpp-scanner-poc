package poc.cppscan.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * facts.json, as plain records. Key order follows docs/output-format.md (which follows the reference file);
 * {@code @JsonPropertyOrder} makes it explicit so it never depends on reflection order.
 */
@JsonPropertyOrder({"targetProjectName", "projectVersion", "targetProjectRootPath", "buildTool", "generatedAt",
        "sourceLanguage", "schemaVersion", "languages", "modules", "deploymentTopology", "aggregatedDependencies",
        "database", "integrations", "deploymentDescriptors", "summary", "codeFlow", "diagnostics"})
public record Facts(String targetProjectName, String projectVersion, String targetProjectRootPath, String buildTool,
                    String generatedAt, String sourceLanguage, String schemaVersion, List<Language> languages,
                    List<Module> modules, DeploymentTopology deploymentTopology,
                    List<Dependency> aggregatedDependencies, Database database, Integrations integrations,
                    List<Object> deploymentDescriptors, Summary summary, CodeFlow codeFlow,
                    List<Diagnostic> diagnostics) {

    @JsonPropertyOrder({"language", "standard", "files"})
    public record Language(String language, String standard, int files) {}

    @JsonPropertyOrder({"name", "groupId", "artifactId", "version", "packaging", "relativePath", "buildFile",
            "hasSource", "dependencies", "components", "exposedApis", "deploymentRole"})
    public record Module(String name, String groupId, String artifactId, String version, String packaging,
                         String relativePath, String buildFile, boolean hasSource, List<Dependency> dependencies,
                         List<Component> components, List<Object> exposedApis, String deploymentRole) {}

    @JsonPropertyOrder({"className", "packageName", "filePath", "sourceFiles", "type", "annotations", "baseClasses",
            "dependencyTypes"})
    public record Component(String className, String packageName, String filePath, List<String> sourceFiles,
                            String type, List<String> annotations, List<BaseClass> baseClasses,
                            List<String> dependencyTypes) {}

    @JsonPropertyOrder({"name", "access", "virtual"})
    public record BaseClass(String name, String access, boolean virtual) {}

    @JsonPropertyOrder({"architectureStyle", "deployableApplicationModules", "sharedLibraryModules",
            "internalLibraryModules", "aggregatorModules", "unclearModules"})
    public record DeploymentTopology(String architectureStyle, List<String> deployableApplicationModules,
                                     List<String> sharedLibraryModules, List<String> internalLibraryModules,
                                     List<String> aggregatorModules, List<String> unclearModules) {}

    @JsonPropertyOrder({"dataSources", "tables", "migrationTool", "migrationScripts"})
    public record Database(List<DataSource> dataSources, List<Table> tables, String migrationTool,
                           List<String> migrationScripts) {}

    /** {@code expression} (the argument's source text) only when the url is dynamic. */
    @JsonPropertyOrder({"module", "url", "username", "sourceFile", "line", "client", "expression"})
    public record DataSource(String module, String url, String username, String sourceFile, int line, String client,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String expression) {}

    @JsonPropertyOrder({"tableName", "source", "module", "sourceFile", "line", "columns", "relationships"})
    public record Table(String tableName, String source, String module, String sourceFile, int line,
                        List<Column> columns, List<Relationship> relationships) {}

    @JsonPropertyOrder({"name", "type", "primaryKey", "nullable", "references"})
    public record Column(String name, String type, boolean primaryKey, boolean nullable,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String references) {}

    @JsonPropertyOrder({"type", "column", "references"})
    public record Relationship(String type, String column, String references) {}

    @JsonPropertyOrder({"messageQueues", "caches", "externalSystems", "ipc"})
    public record Integrations(List<Object> messageQueues, List<Object> caches, List<Object> externalSystems,
                               List<Ipc> ipc) {}

    @JsonPropertyOrder({"type", "role", "operation", "module", "methodId", "sourceFile", "line", "confidence"})
    public record Ipc(String type, String role, String operation, String module, String methodId, String sourceFile,
                      int line, String confidence) {}

    @JsonPropertyOrder({"moduleCount", "deployableApplicationCount", "componentCount", "dependencyCount",
            "tableCount", "dataSourceCount", "messageQueueCount", "cacheCount", "externalSystemCount",
            "deploymentDescriptorCount", "entryPointCount", "methodCount", "callCount", "filesParsed",
            "filesWithErrors", "scanSeconds"})
    public record Summary(int moduleCount, int deployableApplicationCount, int componentCount, int dependencyCount,
                          int tableCount, int dataSourceCount, int messageQueueCount, int cacheCount,
                          int externalSystemCount, int deploymentDescriptorCount, int entryPointCount,
                          int methodCount, int callCount, int filesParsed, int filesWithErrors, long scanSeconds) {}

    @JsonPropertyOrder({"schemaVersion", "projectName", "sourceKind", "entryPoints", "methods"})
    public record CodeFlow(int schemaVersion, String projectName, String sourceKind, List<EntryPoint> entryPoints,
                           List<Method> methods) {}

    @JsonPropertyOrder({"id", "kind", "module", "className", "methodId"})
    public record EntryPoint(String id, String kind, String module, String className, String methodId) {}

    @JsonPropertyOrder({"id", "module", "className", "componentType", "methodName", "parameterTypes", "returnType",
            "virtual", "doc", "transactional", "calls", "exits", "sourceFile", "line"})
    public record Method(String id, String module, String className, String componentType, String methodName,
                         List<String> parameterTypes, String returnType, boolean virtual, String doc,
                         // Written only when true (T7), as in the reference file.
                         @JsonInclude(JsonInclude.Include.NON_NULL) Boolean transactional,
                         List<Call> calls, List<Exit> exits, String sourceFile, int line) {}

    /** sql/operation/tables/table/expression are T7 DATA_ACCESS keys, omitted when null. */
    @JsonPropertyOrder({"kind", "targetClass", "targetMethod", "targetMethodIds", "resolution", "sql", "operation",
            "tables", "table", "expression", "seq", "controlPath", "async", "line"})
    public record Call(String kind, String targetClass, String targetMethod, List<String> targetMethodIds,
                       String resolution,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String sql,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String operation,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<String> tables,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String table,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String expression,
                       int seq, List<ControlStep> controlPath, boolean async, int line) {}

    @JsonPropertyOrder({"kind", "condition", "branch", "line"})
    public record ControlStep(String kind, String condition, String branch, int line) {}

    @JsonPropertyOrder({"kind", "controlPath", "line"})
    public record Exit(String kind, List<ControlStep> controlPath, int line) {}

    @JsonPropertyOrder({"file", "severity", "message"})
    public record Diagnostic(String file, String severity, String message) {}

    /** One out/flows/&lt;entry-point-id&gt;.json file. */
    @JsonPropertyOrder({"entryPoint", "generatedAt", "maxDepth", "participants", "steps", "methods", "omitted",
            "truncated"})
    public record Flow(FlowEntry entryPoint, String generatedAt, int maxDepth, List<Participant> participants,
                       List<Step> steps, List<Method> methods, Map<String, Integer> omitted, boolean truncated) {}

    @JsonPropertyOrder({"id", "kind", "methodId"})
    public record FlowEntry(String id, String kind, String methodId) {}

    @JsonPropertyOrder({"id", "kind", "module"})
    public record Participant(String id, String kind, String module) {}

    @JsonPropertyOrder({"depth", "from", "to", "seq", "controlPath", "async", "line", "file"})
    public record Step(int depth, String from, String to, int seq, List<ControlStep> controlPath, boolean async,
                       int line, String file) {}
}
