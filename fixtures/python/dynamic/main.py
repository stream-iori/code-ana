from typing import Any

def unknown(value: Any, name: str):
    value.run()
    getattr(value, name)()
    __import__(name).run()

def replace(function):
    def wrapper(*args: Any, **kwargs: Any):
        return function(*args, **kwargs)
    return wrapper

@replace
def decorated():
    return 1
