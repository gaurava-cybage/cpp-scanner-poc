package poc.cppscan.parse;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.clang.CXDiagnostic;
import org.bytedeco.llvm.clang.CXFile;
import org.bytedeco.llvm.clang.CXIndex;
import org.bytedeco.llvm.clang.CXString;
import org.bytedeco.llvm.clang.CXTranslationUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.bytedeco.llvm.global.clang.*;

public class ClangParser {

    public static class Diagnostic {
        public final String severity;
        public final String message;
        public final String file;
        public final int line;

        Diagnostic(String severity, String message, String file, int line) {
            this.severity = severity;
            this.message = message;
            this.file = file;
            this.line = line;
        }

        public boolean isError() {
            return "ERROR".equals(severity) || "FATAL".equals(severity);
        }

        @Override
        public String toString() {
            return String.format("[%s] %s:%d %s", severity, file, line, message);
        }
    }

    public static class Result {
        public final int errorCode;
        public final List<Diagnostic> diagnostics;

        Result(int errorCode, List<Diagnostic> diagnostics) {
            this.errorCode = errorCode;
            this.diagnostics = diagnostics;
        }

        public boolean isClean() {
            return errorCode == CXError_Success && diagnostics.stream().noneMatch(Diagnostic::isError);
        }
    }

    public Result parseFile(String filePath, List<String> args) {
        return parseFile(filePath, args, null);
    }

    /**
     * Parses one file. When libclang produced a translation unit, {@code onTranslationUnit} runs before the unit is
     * disposed, also when the file has errors (libclang recovers, so most of the AST is still there).
     */
    public Result parseFile(String filePath, List<String> args, Consumer<CXTranslationUnit> onTranslationUnit) {
        CXIndex index = clang_createIndex(0, 0);
        PointerPointer<BytePointer> argv = new PointerPointer<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            argv.put(i, new BytePointer(args.get(i)));
        }
        CXTranslationUnit tu = new CXTranslationUnit();
        // KeepGoing: a missing third-party header is a fatal error, and without this flag clang stops reading the
        // #includes that follow it, so unrelated code in the file degrades too.
        int err = clang_parseTranslationUnit2(index, new BytePointer(filePath), argv, args.size(),
                null, 0, CXTranslationUnit_DetailedPreprocessingRecord | CXTranslationUnit_KeepGoing, tu);

        List<Diagnostic> diagnostics = new ArrayList<>();
        if (err == CXError_Success) {
            int n = clang_getNumDiagnostics(tu);
            for (int i = 0; i < n; i++) {
                CXDiagnostic d = clang_getDiagnostic(tu, i);
                CXFile file = new CXFile();
                int[] line = new int[1], col = new int[1], offset = new int[1];
                clang_getSpellingLocation(clang_getDiagnosticLocation(d), file, line, col, offset);
                String fileName = file.isNull() ? "" : str(clang_getFileName(file));
                diagnostics.add(new Diagnostic(severity(clang_getDiagnosticSeverity(d)),
                        str(clang_getDiagnosticSpelling(d)), fileName, line[0]));
                clang_disposeDiagnostic(d);
            }
            if (onTranslationUnit != null) {
                try {
                    onTranslationUnit.accept(tu);
                } catch (RuntimeException e) {
                    System.err.println("  extraction failed for " + filePath + ": " + e);
                }
            }
            clang_disposeTranslationUnit(tu);
        }
        clang_disposeIndex(index);
        return new Result(err, diagnostics);
    }

    private static String str(CXString s) {
        BytePointer bp = clang_getCString(s);
        String result = bp == null ? "" : bp.getString();
        clang_disposeString(s);
        return result;
    }

    private static String severity(int s) {
        if (s == CXDiagnostic_Fatal) return "FATAL";
        if (s == CXDiagnostic_Error) return "ERROR";
        if (s == CXDiagnostic_Warning) return "WARNING";
        if (s == CXDiagnostic_Note) return "NOTE";
        return "IGNORED";
    }
}
