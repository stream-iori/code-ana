"""Read graph wire evidence lazily; retain original facts for exact delta checks."""
from collections.abc import Mapping


SINGLE = frozenset(('enclosing', 'component', 'extends', 'super', 'upper', 'lower', 'return', 'receiver'))
MANY = frozenset(('arguments', 'bounds', 'alternatives', 'parameters', 'throws', 'type_variables'))


class TypeAttributes(Mapping):
    def __init__(self, attributes):
        self.raw = attributes
        self.nodes = {}

    def __iter__(self):
        return iter(self.raw)

    def __len__(self):
        return len(self.raw)

    def __getitem__(self, key):
        return self.read(self.raw[key])

    def node(self, graph, reference):
        if reference == -1:
            return None
        payload = self.raw['java_type_graphs'][graph]
        if type(reference) is not int or not 0 <= reference < len(payload['nodes']):
            raise ValueError('TYPE_VIEW_NODE_REFERENCE')
        key = (graph, reference)
        if key not in self.nodes:
            self.nodes[key] = TypeNode(self, graph, reference)
        return self.nodes[key]

    def read(self, value):
        if isinstance(value, dict):
            if set(value) == {'$java_type'}:
                graph, slot = value['$java_type']
                payload = self.raw['java_type_graphs'][graph]
                return self.node(graph, payload['roots'][slot])
            if set(value) == {'$java_object'}:
                return Metadata(self, dict(value['$java_object']))
            return Metadata(self, value)
        if isinstance(value, list):
            return [self.read(item) for item in value]
        return value


class Metadata(Mapping):
    def __init__(self, attributes, raw):
        self.attributes, self.raw = attributes, raw

    def __iter__(self):
        return iter(self.raw)

    def __len__(self):
        return len(self.raw)

    def __getitem__(self, key):
        return self.attributes.read(self.raw[key])


class TypeNode(Metadata):
    def __init__(self, attributes, graph, reference):
        super().__init__(attributes, attributes.raw['java_type_graphs'][graph]['nodes'][reference])
        self.graph, self.reference = graph, reference

    def __getitem__(self, key):
        value = self.raw[key]
        if key in SINGLE:
            return self.attributes.node(self.graph, value)
        if key in MANY:
            return [self.attributes.node(self.graph, child) for child in value]
        return self.attributes.read(value)


def type_attributes(fact):
    attributes = fact['attributes']
    return TypeAttributes(attributes) if 'java_type_graphs' in attributes else attributes
