package poc.cppscan.rules;

import poc.cppscan.extract.CallInfo;
import poc.cppscan.extract.FunctionInfo;

import java.util.ArrayList;
import java.util.List;

/** T7 Winsock rule: calls whose declaration is in a file ending with winsock2.h. */
public class WinsockRule {

    /** One integrations.ipc entry. */
    public record IpcFact(String role, String operation, String module, String methodId, String sourceFile, int line) {}

    public final List<IpcFact> ipc = new ArrayList<>();

    public static boolean isAnchor(String declFileLower) {
        return declFileLower.endsWith("/winsock2.h") || declFileLower.equals("winsock2.h");
    }

    /** SERVER / CLIENT / IO / SETUP (docs/output-format.md), or null for other Winsock functions (htons, ...). */
    static String role(String function) {
        return switch (function) {
            case "bind", "listen", "accept" -> "SERVER";
            case "connect" -> "CLIENT";
            case "send", "recv", "sendto", "recvfrom" -> "IO";
            case "WSAStartup", "socket", "closesocket" -> "SETUP";
            default -> null;
        };
    }

    public void apply(FunctionInfo fn, CallInfo ci) {
        ci.kind = "IPC";
        ci.targetClass = "winsock";
        String role = role(ci.targetMethod);
        if (role != null) {
            ipc.add(new IpcFact(role, ci.targetMethod, fn.module, fn.id(), fn.filePath, ci.line));
        }
    }
}
