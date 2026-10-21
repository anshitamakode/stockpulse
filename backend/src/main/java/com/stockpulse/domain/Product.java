package com.stockpulse.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal currentPrice;

    private int stockLevel;

    private int reorderThreshold;

    /** Current demand estimate in units/day: baseline + units sold inside the rolling window. */
    private int demandVelocity;

    /** Normal demand in units/day; what a spike is measured against. */
    private int baselineVelocity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    /**
     * Sprint 2 extension point (cost, competitor price, supplier lead time, case pack…).
     * Free-form attributes let new signals reach the engine and the prompt without a schema
     * migration; promote a key to a real column once it is load-bearing.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_attributes", joinColumns = @JoinColumn(name = "product_id"))
    @MapKeyColumn(name = "attr_key")
    @Column(name = "attr_value")
    private Map<String, String> attributes = new HashMap<>();

    @Version
    private Long version;

    public Product(String id, String sku, String name, Category category, BigDecimal currentPrice,
                   int stockLevel, int reorderThreshold, int demandVelocity, ProductStatus status) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.currentPrice = currentPrice;
        this.stockLevel = stockLevel;
        this.reorderThreshold = reorderThreshold;
        this.demandVelocity = demandVelocity;
        this.baselineVelocity = demandVelocity;
        this.status = status;
    }

    public boolean isBelowThreshold() {
        return stockLevel < reorderThreshold;
    }

    public void refreshStatus(boolean hasOpenPricingSuggestion) {
        this.status = ProductStatus.derive(status, stockLevel, hasOpenPricingSuggestion);
    }
}
