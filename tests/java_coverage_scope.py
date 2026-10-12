"""Keep analyzer regression tests separate from Java sources used as inputs."""
import re


def analyzer_test_source(path):
    return re.fullmatch(r'anatomist-(core|app|framework-spring|framework-sofa)/src/test/.*\.java', path) is not None
