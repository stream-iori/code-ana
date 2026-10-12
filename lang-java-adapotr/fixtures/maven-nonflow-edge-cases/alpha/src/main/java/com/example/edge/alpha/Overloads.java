package com.example.edge.alpha;

public class Overloads {
    int idle;
    void sink(String value) {}
    void sink(int value) {}
    void pick(String value) { sink(value); }
    void pick(int value) { sink(value); }
    void pickExtra(String value) {}
    void noop() {}
}
