package com.skritped.novacrates.model;

public class CostDefinition {
    private final String type;
    private final double amount;
    private final String material;

    public CostDefinition(String type, double amount, String material) {
        this.type = type == null ? "NONE" : type;
        this.amount = amount;
        this.material = material;
    }

    public String getType() { return type; }
    public double getAmount() { return amount; }
    public String getMaterial() { return material; }

    public boolean isFree() {
        return type == null || type.equalsIgnoreCase("NONE") || amount <= 0;
    }
}
