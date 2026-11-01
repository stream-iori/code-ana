package com.anatomist.worker;

import com.sun.tools.javac.parser.JavaTokenizer;
import com.sun.tools.javac.parser.JavadocTokenizer;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;
import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.tree.JCTree;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Java 8 compiler-side comment lexer. Compiler objects never escape these detached results. */
final class WorkerComments {
    interface Admission {
        void charge(long work, long nodes, long bytes);
    }

    static final class Segment {
        final int bodyStart, bodyEnd, sourceStart, sourceEnd;
        final boolean linear;
        Segment(int bodyStart, int bodyEnd, int sourceStart, int sourceEnd, boolean linear) {
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
            this.linear = linear;
        }
    }

    /** All offsets are original UTF-16 half-open here; the host converts to UTF-8 once. */
    static final class Comment {
        final String kind, body;
        final int start, end;
        final List<Segment> segments;
        final List<Integer> documentOwners = new ArrayList<Integer>();
        Comment(String kind, int start, int end, String body, List<Segment> segments) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.body = body;
            this.segments = Collections.unmodifiableList(segments);
        }
    }

    private static void attachDocuments(final Tape tape, final CompilationUnitTree unit,
                                       final java.util.IdentityHashMap<Tree, Integer> ids) {
        final Admission admission = tape.admission;
        final com.sun.tools.javac.tree.DocCommentTable table = ((JCTree.JCCompilationUnit) unit).docComments;
        if (table == null) return;
        new TreeScanner<Void, Void>() {
            private void attach(Tree node) {
                admission.charge(1, 0, 0);
                Integer id = ids.get(node);
                if (id == null) return;
                Comment comment = tape.tokens.get(table.getComment((JCTree) node));
                if (comment != null && comment.kind.equals("doc")) {
                    admission.charge(1, 1, 64);
                    comment.documentOwners.add(id);
                }
            }
            @Override public Void visitClass(ClassTree node, Void unused) { attach(node); return super.visitClass(node, unused); }
            @Override public Void visitMethod(MethodTree node, Void unused) { attach(node); return super.visitMethod(node, unused); }
            @Override public Void visitVariable(VariableTree node, Void unused) { attach(node); return super.visitVariable(node, unused); }
        }.scan(unit, null);
    }

    /** Installed only in the selected compiler process, before its parser is initialized. */
    static final class Recorder {
        final Context context = new Context();
        final java.util.Map<String, long[]> reservations;
        final java.util.Set<String> declarations;
        final java.util.Map<String, Tape> tapes = new java.util.HashMap<String, Tape>();
        final int release;
        Recorder(java.util.Map<String, long[]> reservations, java.util.Set<String> declarations, int release) {
            this.reservations = reservations;
            this.declarations = declarations;
            this.release = release;
            context.put(ScannerFactory.scannerFactoryKey, new Context.Factory<ScannerFactory>() {
                public ScannerFactory make(Context current) { return new RecordingFactory(current, Recorder.this); }
            });
        }
        List<Comment> comments(String path, CompilationUnitTree unit, java.util.IdentityHashMap<Tree, Integer> ids) {
            Tape tape = tapes.get(path);
            if (tape == null) return Collections.emptyList();
            attachDocuments(tape, unit, ids);
            return tape.comments;
        }
    }

    private static final class Tape {
        final String raw;
        final Admission admission;
        final List<Comment> comments = new ArrayList<Comment>();
        final java.util.IdentityHashMap<Tokens.Comment, Comment> tokens = new java.util.IdentityHashMap<Tokens.Comment, Comment>();
        int lastTokenEnd;
        boolean finished;
        Tape(String raw, Admission admission) { this.raw = raw; this.admission = admission; }
        void record(int start, int end, Tokens.Comment.CommentStyle style, Tokens.Comment token) {
            start = boundary(raw, Math.min(start, raw.length()));
            end = boundary(raw, Math.min(end, raw.length()));
            if (start >= end) return;
            admission.charge(1, 1, 256);
            Comment comment = decode(raw, start, end, style, admission);
            comments.add(comment);
            tokens.put(token, comment);
        }
        void finish(int release) {
            if (finished) return;
            finished = true;
            int start = lastTokenEnd;
            if (!comments.isEmpty()) start = Math.max(start, comments.get(comments.size() - 1).end);
            while (start < raw.length()) {
                admission.charge(1, 0, 0);
                char value = raw.charAt(start);
                if (value != ' ' && value != '\t' && value != '\f' && value != '\r' && value != '\n') break;
                start++;
            }
            if (start >= raw.length()) return;
            // javac 8 does not report the final unterminated-by-newline line comment.
            // Re-lex only the gap after its last real token/comment. No sentinel or second
            // parser ever changes the primary compilation unit, diagnostics or source spans.
            admission.charge(1, 1, 64L + 4L * (raw.length() - start));
            for (Comment tail : scan(raw.substring(start), release, admission)) {
                admission.charge(1, 1, 128L + 64L * tail.segments.size());
                List<Segment> shifted = new ArrayList<Segment>();
                for (Segment segment : tail.segments)
                    shifted.add(new Segment(segment.bodyStart, segment.bodyEnd, segment.sourceStart + start, segment.sourceEnd + start, segment.linear));
                comments.add(new Comment(tail.kind, tail.start + start, tail.end + start, tail.body, shifted));
            }
        }
    }

    private static final class RecordingFactory extends ScannerFactory {
        final Recorder recorder;
        final com.sun.tools.javac.util.Log log;
        RecordingFactory(Context context, Recorder recorder) {
            super(context);
            this.recorder = recorder;
            log = com.sun.tools.javac.util.Log.instance(context);
        }
        @Override public com.sun.tools.javac.parser.Scanner newScanner(CharSequence input, boolean keepDocs) {
            JavaFileObject file = log.currentSourceFile();
            if (file == null || !file.toUri().getScheme().equals("file")) return super.newScanner(input, keepDocs);
            String path = new java.io.File(file.toUri()).getAbsolutePath();
            long[] grant = recorder.reservations.get(path);
            if (grant == null || grant[2] == 0 || recorder.declarations.contains(path)) return super.newScanner(input, keepDocs);
            // Doc-reference parsers also use ScannerFactory while the same file is current.
            // Observe only its complete captured source, never a parsed @see fragment.
            try {
                CharSequence original = file.getCharContent(true);
                if (input.length() != original.length()) return super.newScanner(input, keepDocs);
                for (int i = 0; i < input.length(); i++)
                    if (input.charAt(i) != original.charAt(i)) return super.newScanner(input, keepDocs);
            } catch (java.io.IOException failure) { throw new IllegalStateException("captured comment source unavailable", failure); }
            if (recorder.tapes.containsKey(path)) throw new IllegalStateException("duplicate primary source lexer");
            final long[] remaining = grant.clone();
            Admission admission = new Admission() {
                public void charge(long work, long nodes, long bytes) {
                    if (work > remaining[0] || nodes > remaining[1] || bytes > remaining[2])
                        throw new IllegalStateException("JAVAC_COMMENT_RESERVATION_EXCEEDED");
                    remaining[0] -= work; remaining[1] -= nodes; remaining[2] -= bytes;
                }
            };
            admission.charge(1L + input.length(), 1, 4096L + 32L * input.length());
            Tape tape = new Tape(input.toString(), admission);
            recorder.tapes.put(path, tape);
            return new RecordingScanner(this, new RecordingTokenizer(this, tape, recorder.release));
        }
        @Override public com.sun.tools.javac.parser.Scanner newScanner(char[] input, int length, boolean keepDocs) {
            return newScanner(java.nio.CharBuffer.wrap(input, 0, length), keepDocs);
        }
    }
    private static final class RecordingScanner extends com.sun.tools.javac.parser.Scanner {
        RecordingScanner(ScannerFactory factory, JavaTokenizer tokenizer) { super(factory, tokenizer); }
    }
    private static final class RecordingTokenizer extends JavadocTokenizer {
        final Tape tape;
        final int release;
        RecordingTokenizer(ScannerFactory factory, Tape tape, int release) {
            super(factory, java.util.Arrays.copyOf(tape.raw.toCharArray(), tape.raw.length() + 1), tape.raw.length());
            this.tape = tape;
            this.release = release;
        }
        @Override protected Tokens.Comment processComment(int start, int end, Tokens.Comment.CommentStyle style) {
            Tokens.Comment token = super.processComment(start, end, style);
            tape.record(start, end, style, token);
            return token;
        }
        @Override public Tokens.Token readToken() {
            Tokens.Token token = super.readToken();
            if (token.kind == Tokens.TokenKind.EOF) tape.finish(release);
            else tape.lastTokenEnd = Math.max(tape.lastTokenEnd, boundary(tape.raw, Math.min(token.endPos, tape.raw.length())));
            return token;
        }
    }

    static List<Comment> scan(String raw, int release, Admission admission) {
        // Bound javac's own lexical buffers before constructing its tokenizer. This hook
        // must be supplied by the caller; it does not create or reset an analysis account.
        admission.charge(1L + raw.length(), 1, 4096L + 128L * raw.length());
        Context context = new Context();
        Options.instance(context).put("source", release <= 8 ? "1.8" : Integer.toString(release));
        ScannerFactory factory = ScannerFactory.instance(context);
        Lexer lexer = new Lexer(factory, raw, admission);
        while (lexer.readToken().kind != Tokens.TokenKind.EOF) admission.charge(1, 0, 0);
        admission.charge(1, 1, 32L + 8L * lexer.comments.size());
        return Collections.unmodifiableList(lexer.comments);
    }

    private static final class Lexer extends JavaTokenizer {
        final String raw;
        final Admission admission;
        final List<Comment> comments = new ArrayList<Comment>();
        Lexer(ScannerFactory factory, String raw, Admission admission) {
            // javac 8 omits the comment callback for a line comment ending at EOF. A real
            // terminator followed by a harmless token also avoids UnicodeReader trimming
            // that terminator as its final whitespace. Neither sentinel enters a result.
            super(factory, (raw + "\n;").toCharArray(), raw.length() + 2);
            this.raw = raw;
            this.admission = admission;
        }

        @Override
        protected Tokens.Comment processComment(int start, int end, Tokens.Comment.CommentStyle style) {
            start = boundary(raw, Math.min(start, raw.length()));
            end = boundary(raw, Math.min(end, raw.length()));
            if (start < end) {
                admission.charge(1, 1, 128);
                comments.add(decode(raw, start, end, style, admission));
            }
            return super.processComment(start, end, style);
        }
    }

    // javac's bp points at the final hex digit after translating a Unicode escape,
    // including the character immediately AFTER a comment. Recover the original boundary.
    private static int boundary(String raw, int position) {
        if (position >= raw.length() || position < 5) return position;
        for (int i = position - 3; i <= position; i++)
            if (Character.digit(raw.charAt(i), 16) < 0) return position;
        int u = position - 4;
        if (raw.charAt(u) != 'u') return position;
        while (u >= 0 && raw.charAt(u) == 'u') u--;
        return u >= 0 && raw.charAt(u) == '\\' ? u : position;
    }

    private static final class Unit {
        final char value;
        final int start, end;
        Unit(char value, int start, int end) {
            this.value = value;
            this.start = start;
            this.end = end;
        }
    }

    // Post-lexical Unicode translation only: javac already recognized the comment and its
    // boundaries. Do not depend on UnicodeReader's private layout, which changed after 8.
    // Literal backslashes pair up; a translated backslash is never recursively translated.
    private static List<Unit> units(String text, int origin, Admission admission) {
        List<Unit> result = new ArrayList<Unit>();
        for (int at = 0; at < text.length();) {
            int start = at;
            char value = text.charAt(at++);
            if (value == '\\' && at < text.length()) {
                if (text.charAt(at) == '\\') {
                    admission.charge(2, 2, 128);
                    result.add(new Unit('\\', origin + start, origin + at));
                    result.add(new Unit('\\', origin + at, origin + at + 1));
                    at++;
                    continue;
                }
                int hex = at;
                while (hex < text.length() && text.charAt(hex) == 'u') hex++;
                if (hex > at && hex + 4 <= text.length()) {
                    int decoded = 0;
                    boolean valid = true;
                    for (int digit = hex; digit < hex + 4; digit++) {
                        int n = Character.digit(text.charAt(digit), 16);
                        if (n < 0) { valid = false; break; }
                        decoded = decoded * 16 + n;
                    }
                    if (valid) { value = (char) decoded; at = hex + 4; }
                }
            }
            admission.charge(1, 1, 64);
            result.add(new Unit(value, origin + start, origin + at));
        }
        return result;
    }

    private static Comment decode(String raw, int start, int end,
                                  Tokens.Comment.CommentStyle style, Admission admission) {
        admission.charge(1, 1, 64L + 4L * (end - start));
        String slice = raw.substring(start, end);
        List<Unit> units = units(slice, start, admission);
        boolean line = style.name().equals("LINE") || style.name().equals("JAVADOC_LINE");
        boolean doc = style.name().startsWith("JAVADOC");
        int finish = line ? units.size() : Math.max(0, units.size() - 2);
        int begin = Math.min(finish, doc ? 3 : 2);
        StringBuilder body = new StringBuilder();
        List<Segment> map = new ArrayList<Segment>();
        boolean lineStart = true;
        for (int i = begin; i < finish;) {
            admission.charge(1, 0, 0);
            if (doc && lineStart) {
                int marker = i;
                while (marker < finish && (units.get(marker).value == ' ' || units.get(marker).value == '\t')) marker++;
                if (marker < finish && units.get(marker).value == '*') {
                    i = marker + 1;
                    if (i < finish && (units.get(i).value == ' ' || units.get(i).value == '\t')) i++;
                    if (i >= finish) break;
                }
            }
            Unit unit = units.get(i++);
            int bodyStart = body.length(), sourceEnd = unit.end;
            admission.charge(1, 1, 128);
            if (Character.isHighSurrogate(unit.value) && i < finish
                    && Character.isLowSurrogate(units.get(i).value)) {
                Unit low = units.get(i++);
                body.append(unit.value).append(low.value);
                sourceEnd = low.end;
            } else if (Character.isSurrogate(unit.value)) {
                // An escaped unpaired surrogate cannot cross a UTF-8 boundary. Preserve
                // its literal spelling, rather than silently replacing it during transport.
                body.append(raw, unit.start, unit.end);
            } else {
                body.append(unit.value);
            }
            boolean linear = body.length() - bodyStart == sourceEnd - unit.start;
            for (int p = bodyStart; linear && p < body.length(); p++)
                linear = body.charAt(p) == raw.charAt(unit.start + p - bodyStart);
            Segment previous = map.isEmpty() ? null : map.get(map.size() - 1);
            if (linear && previous != null && previous.linear && previous.sourceEnd == unit.start) {
                map.set(map.size() - 1, new Segment(previous.bodyStart, body.length(), previous.sourceStart, sourceEnd, true));
            } else {
                map.add(new Segment(bodyStart, body.length(), unit.start, sourceEnd, linear));
            }
            lineStart = unit.value == '\n' || unit.value == '\r';
        }
        admission.charge(1, 1, 64L + 4L * body.length());
        return new Comment(line ? "line" : doc ? "doc" : "block", start, end, body.toString(), map);
    }
}
