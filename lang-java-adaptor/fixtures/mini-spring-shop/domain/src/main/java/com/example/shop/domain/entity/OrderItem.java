package com.example.shop.domain.entity;

public class OrderItem {
    private String sku;
    private double price;
    private int quantity;

    public OrderItem() {}

    public OrderItem(String sku, double price, int quantity) {
        this.sku = sku;
        this.price = price;
        this.quantity = quantity;
    }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
