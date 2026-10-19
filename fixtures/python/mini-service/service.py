from dataclasses import dataclass
from typing import Protocol

class Repository(Protocol):
    def get(self, key: str) -> str: ...

@dataclass
class MemoryRepository:
    value: str
    def get(self, key: str) -> str:
        return self.value

def repository():
    return MemoryRepository('fixture')

def route(key: str):
    return repository().get(key)
