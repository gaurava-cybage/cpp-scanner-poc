package poc.cppscan.extract;

import java.util.ArrayList;
import java.util.List;

/** One call inside a function body (later codeFlow.methods[].calls[]). */
public class CallInfo {
    /** CALL; T7 turns anchor calls into DATA_ACCESS / IPC. */
    public String kind = "CALL";
    public String targetClass = "";
    public String targetMethod = "";
    public List<String> targetMethodIds = new ArrayList<>();
    /** EXACT, VIRTUAL or UNRESOLVED. */
    public String resolution;
    public int seq;
    public List<ControlStep> controlPath = List.of();
    public boolean async;
    public int line;

    // T7 DATA_ACCESS keys (null when not known).
    public String sql;
    public String operation;
    public List<String> tables;
    public String expression;

    // Not in the JSON.
    /** USR of the called declaration ("" when unresolved); used to expand VIRTUAL calls. */
    public String targetUsr = "";
    /** Absolute path of the file declaring the callee, for the T7 anchor rules ("" when unresolved). */
    public String declFile = "";
    /** The callee is declared outside the solution (third-party or system anchor/async call). */
    public boolean external;

    /** One enclosing control statement, outermost first. */
    public record ControlStep(String kind, String condition, String branch, int line) {
        @Override
        public String toString() {
            return kind + "(" + condition + ")" + (branch.isEmpty() ? "" : "[" + branch + "]") + "@" + line;
        }
    }

    /** A return or throw point. */
    public record ExitInfo(String kind, List<ControlStep> controlPath, int line) {}
}
