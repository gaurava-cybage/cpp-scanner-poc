package poc.cppscan.extract;

import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.clang.CXCursor;
import org.bytedeco.llvm.clang.CXFile;
import org.bytedeco.llvm.clang.CXSourceLocation;
import org.bytedeco.llvm.clang.CXSourceRange;
import org.bytedeco.llvm.clang.CXToken;
import org.bytedeco.llvm.clang.CXTranslationUnit;

import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.llvm.global.clang.*;

/** Source tokens of a cursor (clang_tokenize) and helpers to turn them back into text or string values. */
public final class SourceText {

    private SourceText() {}

    public record Tok(String text, int kind, int start, int end) {
        boolean isStringLiteral() {
            return kind == CXToken_Literal && text.endsWith("\"") && text.indexOf('"') >= 0
                    && text.substring(0, text.indexOf('"')).matches("(u8|u|U|L)?R?");
        }
    }

    public static List<Tok> tokens(CXTranslationUnit tu, CXCursor c) {
        List<Tok> result = new ArrayList<>();
        PointerPointer<CXToken> out = new PointerPointer<>(1);
        IntPointer count = new IntPointer(1);
        clang_tokenize(tu, expansionRange(tu, clang_getCursorExtent(c)), out, count);
        int n = count.get();
        if (n == 0 || out.get(0) == null) {
            return result;
        }
        CXToken base = new CXToken(out.get(0));
        for (int i = 0; i < n; i++) {
            CXToken t = base.getPointer(i);
            CXSourceRange r = clang_getTokenExtent(tu, t);
            result.add(new Tok(Extractor.str(clang_getTokenSpelling(tu, t)), clang_getTokenKind(t),
                    offset(clang_getRangeStart(r)), offset(clang_getRangeEnd(r))));
        }
        clang_disposeTokens(tu, base, n);
        return result;
    }

    /** Tokens [from, to) joined with one space where the source had whitespace (whitespace collapsed). */
    public static String text(List<Tok> toks, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from && toks.get(i).start() > toks.get(i - 1).end()) {
                sb.append(' ');
            }
            sb.append(toks.get(i).text());
        }
        return sb.toString();
    }

    public static String text(List<Tok> toks) {
        return text(toks, 0, toks.size());
    }

    /**
     * The value when the tokens are only string literals (adjacent literals are joined), optionally wrapped in
     * one pair of ( ) or { }; otherwise null.
     */
    public static String stringValue(List<Tok> toks) {
        int from = 0, to = toks.size();
        while (to - from >= 2 && ((toks.get(from).text().equals("(") && toks.get(to - 1).text().equals(")"))
                || (toks.get(from).text().equals("{") && toks.get(to - 1).text().equals("}")))) {
            from++;
            to--;
        }
        if (from >= to) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            Tok t = toks.get(i);
            if (!t.isStringLiteral()) {
                return null;
            }
            sb.append(unquote(t.text()));
        }
        return sb.toString();
    }

    /** "abc\"d" -> abc"d ; R"x(raw)x" -> raw ; prefixes L/u/U/u8 dropped. */
    static String unquote(String literal) {
        int q = literal.indexOf('"');
        String prefix = literal.substring(0, q);
        String body = literal.substring(q + 1, literal.length() - 1);
        if (prefix.endsWith("R")) {
            int open = body.indexOf('(');
            int close = body.lastIndexOf(')');
            return open >= 0 && close > open ? body.substring(open + 1, close) : body;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch != '\\' || i + 1 >= body.length()) {
                sb.append(ch);
                continue;
            }
            char next = body.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '0' -> sb.append('\0');
                default -> sb.append(next); // \" \\ \' and anything else: the character itself
            }
        }
        return sb.toString();
    }

    /**
     * The extent of an expression that comes from a macro runs from the macro definition to the use, which would
     * tokenize half the file. Rebuild it from the expansion locations (where the code is used): a macro use then
     * gives its name token, and ordinary code is unchanged.
     */
    private static CXSourceRange expansionRange(CXTranslationUnit tu, CXSourceRange extent) {
        CXFile startFile = new CXFile(), endFile = new CXFile();
        int[] l = new int[1], col = new int[1], startOff = new int[1], endOff = new int[1];
        clang_getExpansionLocation(clang_getRangeStart(extent), startFile, l, col, startOff);
        clang_getExpansionLocation(clang_getRangeEnd(extent), endFile, l, col, endOff);
        if (startFile.isNull() || endFile.isNull()
                || !Extractor.str(clang_getFileName(startFile)).equals(Extractor.str(clang_getFileName(endFile)))
                || endOff[0] < startOff[0]) {
            return extent;
        }
        return clang_getRange(clang_getLocationForOffset(tu, startFile, startOff[0]),
                clang_getLocationForOffset(tu, endFile, endOff[0]));
    }

    static int offset(CXSourceLocation loc) {
        CXFile f = new CXFile();
        int[] l = new int[1], col = new int[1], off = new int[1];
        clang_getExpansionLocation(loc, f, l, col, off);
        return off[0];
    }
}
