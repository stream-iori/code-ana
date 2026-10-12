"""Discover Java test declarations without counting fixture strings or comments."""
import re


def code_only(source):
    tokens = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    return tokens.sub(lambda match: ''.join('\n' if c == '\n' else ' ' for c in match[0]), source)


def test_cases(source, filename):
    code = code_only(source)
    package = re.search(r'\bpackage\s+([\w.]+)\s*;', code)
    if not package:
        return []
    return [package[1] + '.' + filename + '#' + name for name in
            re.findall(r'@Test\b[\s\S]*?\bvoid\s+(\w+)\s*\(', code)]


def other_annotations(source):
    return sorted(set(re.findall(r'@(ParameterizedTest|TestFactory|RepeatedTest|Nested)\b', code_only(source))))
