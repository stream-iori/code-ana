package com.example.edge.beta;

public interface Port {
    void send();
}

class PortImpl implements Port {
    public void send() {}
}
