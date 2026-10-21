package com.stockpulse.web.dto;

import com.stockpulse.domain.InventorySnapshot;
import com.stockpulse.domain.SnapshotReason;

import java.math.BigDecimal;
import java.time.Instant;

public record SnapshotDto(SnapshotReason reason, int quantityDelta, int stockLevel, int demandVelocity,
                          BigDecimal price, Instant capturedAt) {

    public static SnapshotDto from(InventorySnapshot s) {
        return new SnapshotDto(s.getReason(), s.getQuantityDelta(), s.getStockLevel(), s.getDemandVelocity(),
                s.getPrice(), s.getCapturedAt());
    }
}
