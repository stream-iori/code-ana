import a


def second() -> str:
    return "ok"


def cycle() -> str:
    return a.first()
