package com.zusby.zkplugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level parser for ZK MVVM annotation expressions found in .zul attribute values.
 * <p>
 * Pure string processing (no PSI), so it is cheap and easy to test. Understands:
 * <ul>
 *     <li>property paths anywhere inside {@code @bind/@load/@save/@init/@ref(...)}, including
 *         nested paths ({@code vm.address.city}) and expressions
 *         ({@code vm.count gt 0 ? vm.name : 'none'})</li>
 *     <li>{@code @command('name', arg=...)} / {@code @global-command('name')}</li>
 *     <li>{@code before='cmd'} / {@code after={'a','b'}} options of {@code @load/@save}</li>
 * </ul>
 * Path roots are reported as-is; callers decide which roots (vm id, form ids) are meaningful.
 */
public final class ZkBindingParser {

    public enum Kind { PROPERTY, COMMAND }

    /** One property-path segment or command name, with offsets relative to the parsed text. */
    public static final class Token {
        public final Kind kind;
        public final int start;
        public final int end;
        /** Path root such as {@code vm} or {@code fx}; {@code null} for commands. */
        public final @Nullable String root;
        /** Path segments between the root and this segment ({@code [address]} for {@code vm.address.city}). */
        public final List<String> qualifier;
        public final String name;

        Token(Kind kind, int start, int end, @Nullable String root, List<String> qualifier, String name) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.root = root;
            this.qualifier = qualifier;
            this.name = name;
        }

        @Override
        public String toString() {
            return kind == Kind.COMMAND ? "command:" + name
                    : root + "." + (qualifier.isEmpty() ? "" : String.join(".", qualifier) + ".") + name;
        }
    }

    /** What is being typed at the end of a (partial) attribute value, for code completion. */
    public static final class CompletionContext {
        public final Kind kind;
        public final @Nullable String root;
        public final List<String> qualifier;
        public final String prefix;

        CompletionContext(Kind kind, @Nullable String root, List<String> qualifier, String prefix) {
            this.kind = kind;
            this.root = root;
            this.qualifier = qualifier;
            this.prefix = prefix;
        }
    }

    private static final Pattern ANNOTATION = Pattern.compile("@([a-zA-Z][a-zA-Z-]*)\\s*\\(");
    private static final Set<String> PROPERTY_ANNOTATIONS = Set.of("bind", "load", "save", "init", "ref");
    private static final Set<String> COMMAND_ANNOTATIONS = Set.of("command", "global-command");
    private static final Pattern COMMAND_OPTION = Pattern.compile("\\b(?:before|after)\\s*=\\s*");

    private static final Pattern PATH_AT_END = Pattern.compile(
            "(?<![\\w.$])([a-zA-Z_][a-zA-Z0-9_]*)((?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)\\.([a-zA-Z0-9_]*)$");
    private static final Pattern COMMAND_NAME_AT_END = Pattern.compile("^\\s*['\"]([a-zA-Z0-9_]*)$");
    private static final Pattern COMMAND_OPTION_AT_END = Pattern.compile(
            "\\b(?:before|after)\\s*=\\s*\\{?(?:\\s*['\"][a-zA-Z0-9_]*['\"]\\s*,)*\\s*['\"]([a-zA-Z0-9_]*)$");

    private ZkBindingParser() {
    }

    /** Returns every property segment and command name referenced in {@code text}. */
    @NotNull
    public static List<Token> parse(@NotNull CharSequence text) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = ANNOTATION.matcher(text);
        int from = 0;
        while (from < text.length() && m.find(from)) {
            String annotation = m.group(1);
            int argsStart = m.end();
            int argsEnd = findClose(text, argsStart);
            if (PROPERTY_ANNOTATIONS.contains(annotation)) {
                scanPaths(text, argsStart, argsEnd, tokens);
                scanCommandOptions(text, argsStart, argsEnd, tokens);
            } else if (COMMAND_ANNOTATIONS.contains(annotation)) {
                scanCommandName(text, argsStart, argsEnd, tokens);
            }
            from = Math.max(argsEnd, argsStart);
        }
        return tokens;
    }

    /**
     * Determines what is being typed at the end of {@code text} (attribute value up to the caret):
     * a property after {@code root.path.} or a command name inside quotes. Returns {@code null}
     * when the caret is not in such a position.
     */
    @Nullable
    public static CompletionContext completionContext(@NotNull CharSequence text) {
        Matcher m = ANNOTATION.matcher(text);
        int from = 0;
        String annotation = null;
        int argsStart = -1;
        while (from < text.length() && m.find(from)) {
            int end = findClose(text, m.end());
            if (end >= text.length()) {          // unclosed: the caret is inside this annotation
                annotation = m.group(1);
                argsStart = m.end();
                break;
            }
            from = end;
        }
        if (annotation == null) return null;
        CharSequence args = text.subSequence(argsStart, text.length());

        if (COMMAND_ANNOTATIONS.contains(annotation)) {
            Matcher cm = COMMAND_NAME_AT_END.matcher(args);
            return cm.find() ? new CompletionContext(Kind.COMMAND, null, List.of(), cm.group(1)) : null;
        }
        if (!PROPERTY_ANNOTATIONS.contains(annotation)) return null;

        if (endsInsideQuote(args)) {
            Matcher om = COMMAND_OPTION_AT_END.matcher(args);
            return om.find() ? new CompletionContext(Kind.COMMAND, null, List.of(), om.group(1)) : null;
        }
        Matcher pm = PATH_AT_END.matcher(args);
        if (!pm.find()) return null;
        return new CompletionContext(Kind.PROPERTY, pm.group(1), splitPath(pm.group(2)), pm.group(3));
    }

    private static List<String> splitPath(String dotted) {
        if (dotted.isEmpty()) return List.of();
        List<String> parts = new ArrayList<>();
        Collections.addAll(parts, dotted.substring(1).split("\\."));
        return parts;
    }

    /** Index of the {@code )} closing the annotation whose arguments start at {@code from}, or text length. */
    private static int findClose(CharSequence text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(text, i, text.length());
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return text.length();
    }

    /** Given the index of an opening quote, returns the index of the closing one (or {@code limit}). */
    private static int skipQuoted(CharSequence text, int quoteIndex, int limit) {
        char q = text.charAt(quoteIndex);
        int j = quoteIndex + 1;
        while (j < limit && text.charAt(j) != q) j++;
        return j;
    }

    private static boolean endsInsideQuote(CharSequence args) {
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\'' || c == '"') {
                int close = skipQuoted(args, i, args.length());
                if (close >= args.length()) return true;
                i = close;
            }
        }
        return false;
    }

    private static void scanPaths(CharSequence text, int from, int to, List<Token> out) {
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(text, i, to) + 1;
                continue;
            }
            boolean boundary = i == from || !isIdentPart(text.charAt(i - 1)) && text.charAt(i - 1) != '.';
            if (!Character.isJavaIdentifierStart(c) || c == '$' || !boundary) {
                i++;
                continue;
            }
            List<String> segments = new ArrayList<>();
            List<int[]> ranges = new ArrayList<>();
            int j = readIdent(text, i, to);
            segments.add(text.subSequence(i, j).toString());
            ranges.add(new int[]{i, j});
            while (j + 1 < to && text.charAt(j) == '.' && Character.isJavaIdentifierStart(text.charAt(j + 1))) {
                int s = j + 1;
                j = readIdent(text, s, to);
                segments.add(text.subSequence(s, j).toString());
                ranges.add(new int[]{s, j});
            }
            // vm.items.size() -> "size" is a method call, not a property
            int count = segments.size();
            if (j < to && text.charAt(j) == '(') count--;
            for (int k = 1; k < count; k++) {
                out.add(new Token(Kind.PROPERTY, ranges.get(k)[0], ranges.get(k)[1], segments.get(0),
                        List.copyOf(segments.subList(1, k)), segments.get(k)));
            }
            i = j;
        }
    }

    private static void scanCommandName(CharSequence text, int from, int to, List<Token> out) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= to || (text.charAt(i) != '\'' && text.charAt(i) != '"')) return;
        addQuotedCommand(text, i, to, out);
    }

    private static void scanCommandOptions(CharSequence text, int from, int to, List<Token> out) {
        Matcher m = COMMAND_OPTION.matcher(text).region(from, to);
        while (m.find()) {
            int i = m.end();
            if (i < to && text.charAt(i) == '{') {
                int close = i;
                while (close < to && text.charAt(close) != '}') close++;
                for (int k = i + 1; k < close; k++) {
                    char c = text.charAt(k);
                    if (c == '\'' || c == '"') k = addQuotedCommand(text, k, close, out);
                }
            } else if (i < to && (text.charAt(i) == '\'' || text.charAt(i) == '"')) {
                addQuotedCommand(text, i, to, out);
            }
        }
    }

    /** Adds the command name quoted at {@code quoteIndex}; returns the closing quote index. */
    private static int addQuotedCommand(CharSequence text, int quoteIndex, int limit, List<Token> out) {
        int close = skipQuoted(text, quoteIndex, limit);
        String name = text.subSequence(quoteIndex + 1, Math.min(close, limit)).toString();
        if (close < limit && name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            out.add(new Token(Kind.COMMAND, quoteIndex + 1, close, null, List.of(), name));
        }
        return close;
    }

    private static int readIdent(CharSequence text, int from, int to) {
        int j = from;
        while (j < to && isIdentPart(text.charAt(j))) j++;
        return j;
    }

    private static boolean isIdentPart(char c) {
        return Character.isJavaIdentifierPart(c) && c != '$';
    }
}
