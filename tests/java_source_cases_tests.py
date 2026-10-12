import unittest
from java_source_cases import other_annotations, test_cases


class SourceCases(unittest.TestCase):
    def test_fixture_annotations_and_comments_do_not_create_cases(self):
        source = '''package p;
        // @Test void phantom() {}
        /* @Test void phantomTwo() {} */
        class Real { @Test void actual() {
            String java = """@Test void fixture() {} @ParameterizedTest""";
            String escaped = "\\\" @Test void another() {}";
        }}'''
        self.assertEqual(test_cases(source, 'Real'), ['p.Real#actual'])
        self.assertEqual(other_annotations(source), [])

    def test_argument_annotations_and_multiline_methods_are_preserved(self):
        self.assertEqual(test_cases('package p; @Test\nvoid real(@TempDir Path p) {}', 'Real'),
                         ['p.Real#real'])

    def test_nonordinary_test_annotations_are_disclosed(self):
        self.assertEqual(other_annotations('package p; @Nested class N { @ParameterizedTest void p() {} }'),
                         ['Nested', 'ParameterizedTest'])


if __name__ == '__main__':
    unittest.main()
