"""Explicit JSON CLI contract for test/export consumers; no legacy text parsing."""
import json


def decode_response(stdout):
    value = json.loads(stdout)
    records = value.get('records', [event['record'] for event in value.get('events', []) if 'record' in event])
    terminal = dict(value['result'], evidence=value['evidence'], cursor=value['delivery']['cursor'])
    return records, terminal
