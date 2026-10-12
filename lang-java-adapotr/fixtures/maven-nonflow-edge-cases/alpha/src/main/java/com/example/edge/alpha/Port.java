package com.example.edge.alpha;

public interface Port {
    void send();
}

class PortImpl implements Port {
    public void send() {}
}
