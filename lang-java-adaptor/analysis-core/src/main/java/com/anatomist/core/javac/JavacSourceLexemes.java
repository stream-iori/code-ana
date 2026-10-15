package com.anatomist.core.javac;

import java.util.*;

/** Original-offset lexical names, including Unicode escapes; comments and literals cannot match. */
final class JavacSourceLexemes {
    record Lexeme(String text, int start, int end) {}

    private final List<Lexeme> lexemes = new ArrayList<>();

    JavacSourceLexemes(String raw) {
        StringBuilder decoded = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        int slashes = 0;
        for (int at = 0; at < raw.length(); ) {
            int start = at;
            char value = raw.charAt(at++);
            if (value == '\\' && slashes % 2 == 0 && at < raw.length() && raw.charAt(at) == 'u') {
                int hex = at;
                while (hex < raw.length() && raw.charAt(hex) == 'u') hex++;
                if (hex + 4 <= raw.length()) {
                    try {
                        value = (char) Integer.parseInt(raw.substring(hex, hex + 4), 16);
                        at = hex + 4;
                    } catch (NumberFormatException ignored) {
                        /* The compiler reports malformed escapes. */
                    }
                }
            }
            decoded.append(value);
            offsets.add(start);
            slashes = value == '\\' ? slashes + 1 : 0;
        }
        offsets.add(raw.length());
        String source = decoded.toString();
        for (int at = 0; at < source.length(); ) {
            char ch = source.charAt(at);
            if (Character.isWhitespace(ch)) {
                at++;
                continue;
            }
            if (source.startsWith("//", at)) {
                int end = source.indexOf('\n', at + 2);
                at = end < 0 ? source.length() : end;
                continue;
            }
            if (source.startsWith("/*", at)) {
                int end = source.indexOf("*/", at + 2);
                at = end < 0 ? source.length() : end + 2;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                boolean text = source.startsWith("\"\"\"", at);
                int delimiter = text ? 3 : 1;
                at += delimiter;
                while (at < source.length()) {
                    if (source.charAt(at) == '\\') {
                        at += 2;
                        continue;
                    }
                    if (text ? source.startsWith("\"\"\"", at) : source.charAt(at) == ch) {
                        at += delimiter;
                        break;
                    }
                    at++;
                }
                continue;
            }
            int start = at;
            int codePoint = source.codePointAt(at);
            at += Character.charCount(codePoint);
            if (Character.isJavaIdentifierStart(codePoint))
                while (at < source.length()
                        && Character.isJavaIdentifierPart(source.codePointAt(at)))
                    at += Character.charCount(source.codePointAt(at));
            lexemes.add(
                    new Lexeme(
                            source.substring(start, at), offsets.get(start), offsets.get(at) - 1));
        }
    }

    Lexeme name(
            int start,
            int end,
            String name,
            boolean callable,
            boolean typeDeclaration,
            boolean first) {
        int left = 0, right = lexemes.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (lexemes.get(mid).start() < start) left = mid + 1;
            else right = mid;
        }
        int parentheses = 0;
        Lexeme found = null;
        for (int index = left; index < lexemes.size(); index++) {
            Lexeme token = lexemes.get(index);
            if (token.start() >= end) break;
            String previous = index == 0 ? "" : lexemes.get(index - 1).text();
            String next = index + 1 == lexemes.size() ? "" : lexemes.get(index + 1).text();
            if (token.text().equals(name)
                    && token.end() < end
                    && (!callable
                            || parentheses == 0
                                    && !previous.equals("@")
                                    && (next.equals("(") || next.equals("{")))
                    && (!typeDeclaration
                            || Set.of("class", "interface", "enum", "record").contains(previous))) {
                found = token;
                if (first) return found;
            }
            if (token.text().equals("(")) parentheses++;
            if (token.text().equals(")")) parentheses--;
        }
        return found;
    }

    int bodyStart(long begin, long end) {
        int parentheses = 0;
        int left = 0, right = lexemes.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (lexemes.get(mid).start() < begin) left = mid + 1;
            else right = mid;
        }
        for (int index = left; index < lexemes.size(); index++) {
            Lexeme token = lexemes.get(index);
            if (token.start() >= end) break;
            if (token.text().equals("(")) parentheses++;
            else if (token.text().equals(")")) parentheses--;
            else if (token.text().equals("{") && parentheses == 0) return token.start();
        }
        return -1;
    }

    boolean variadic(long begin, long end) {
        int left = 0, right = lexemes.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (lexemes.get(mid).start() < begin) left = mid + 1;
            else right = mid;
        }
        int dots = 0;
        for (int index = left; index < lexemes.size(); index++) {
            Lexeme token = lexemes.get(index);
            if (token.start() >= end) break;
            dots = token.text().equals(".") ? dots + 1 : 0;
            if (dots == 3) return true;
        }
        return false;
    }
}
