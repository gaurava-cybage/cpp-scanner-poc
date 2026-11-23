package poc.cppscan.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One entry of modules[].dependencies (docs/output-format.md). {@code internal} is null (omitted) for libraries. */
@JsonPropertyOrder({"groupId", "artifactId", "version", "scope", "linkage", "internal"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Dependency(String groupId, String artifactId, String version, String scope,
                         String linkage, Boolean internal) {

    @Override
    public String toString() {
        return artifactId + " [" + groupId + ", " + linkage + ", version " + version
                + (Boolean.TRUE.equals(internal) ? ", internal" : "") + "]";
    }
}
