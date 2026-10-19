from typing import Any, Protocol
from dataclasses import dataclass

class Service:
    def run(self) -> str:
        return "ok"

class Other:
    def run(self) -> str:
        return "other"

class Child(Service):
    pass

def typed_factory() -> Service:
    return Service()

def untyped_factory():
    return Service()

def make_callback():
    return typed_factory

class Runner(Protocol):
    def run(self) -> str: ...

@dataclass
class Box:
    service: Service

def probe(flag: bool, dynamic: Any, protocol: Runner):
    direct = Service()
    direct.run() # direct
    typed = typed_factory()
    typed.run() # typed_factory
    untyped = untyped_factory()
    untyped.run() # untyped_factory
    child = Child()
    child.run() # inheritance
    union = Service() if flag else Other()
    union.run() # union
    callback = make_callback()
    returned = callback()
    returned.run() # higher_order
    box = Box(Service())
    box.service.run() # dataclass
    protocol.run() # protocol
    dynamic.run() # dynamic
