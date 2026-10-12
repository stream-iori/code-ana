package com.example;

interface Contract {
    void execute();
    default void ready() {}
    private void helper() {}
}
