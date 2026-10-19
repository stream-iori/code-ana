from utils import from_a
from shared import shared_value

def exercise_a() -> int:
    return from_a() + shared_value()

raise RuntimeError("analysis must never execute this module")
