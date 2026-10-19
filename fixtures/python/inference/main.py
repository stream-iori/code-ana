from typing import Any


class Service:
    def run(self) -> str:
        return "service"


class Other:
    def run(self) -> str:
        return "other"


def factory():
    return Service()


def higher_order():
    return factory


def exercise(value: Any, flag: bool):
    factory().run()
    higher_order()().run()
    selected = Service() if flag else Other()
    selected.run()
    value.run()


def partial(value: Service | Any):
    value.run()
