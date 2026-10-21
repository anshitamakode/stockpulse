package com.stockpulse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a product's inventory state after each change. Doubles as the sales
 * log (ORDER rows carry a negative quantityDelta) used to compute rolling demand velocity.
 */
@Entity
@Table(name = "inventory_snapshots",
        indexes = @Index(name = "idx_snapshot_product_time", columnList = "product_id, captured_at"))
@Getter
@Setter
@NoArgsConstructor
public class InventorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Product product;

    private int stockLevel;

    /** Signed change that produced this snapshot: -3 for an order of 3, +40 for a restock. */
    private int quantityDelta;

    private int demandVelocity;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SnapshotReason reason;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    public static InventorySnapshot of(Product product, int quantityDelta, SnapshotReason reason, Instant at) {
        InventorySnapshot s = new InventorySnapshot();
        s.product = product;
        s.stockLevel = product.getStockLevel();
        s.quantityDelta = quantityDelta;
        s.demandVelocity = product.getDemandVelocity();
        s.price = product.getCurrentPrice();
        s.reason = reason;
        s.capturedAt = at;
        return s;
    }
}
