package com.example.shop.notification;

import java.util.List;
import java.util.Map;

public class NotificationRegistry {
    private List<NotificationChannel> orderedChannels;
    private Map<String, NotificationChannel> aliases;

    public List<NotificationChannel> getOrderedChannels() { return orderedChannels; }
    public void setOrderedChannels(List<NotificationChannel> orderedChannels) {
        this.orderedChannels = orderedChannels;
    }
    public Map<String, NotificationChannel> getAliases() { return aliases; }
    public void setAliases(Map<String, NotificationChannel> aliases) {
        this.aliases = aliases;
    }
}

