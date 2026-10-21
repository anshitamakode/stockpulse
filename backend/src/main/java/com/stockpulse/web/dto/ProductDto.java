package com.stockpulse.web.dto;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.ProductStatus;

import java.math.BigDecimal;
import java.util.Map;

/** @param daysOfCover null when there is no recent demand */
public record ProductDto(
        String id,
        String sku,
        String name,
        Category category,
        BigDecimal currentPrice,
        int stockLevel,
        int reorderThreshold,
        int demandVelocity,
        int baselineVelocity,
        Double daysOfCover,
        boolean belowThreshold,
        ProductStatus status,
        Map<String, String> attributes) {

    public static ProductDto from(Product p) {
        Double cover = p.getDemandVelocity() > 0
                ? Math.round(10.0 * p.getStockLevel() / p.getDemandVelocity()) / 10.0
                : null;
        return new ProductDto(p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getCurrentPrice(),
                p.getStockLevel(), p.getReorderThreshold(), p.getDemandVelocity(), p.getBaselineVelocity(),
                cover, p.isBelowThreshold(), p.getStatus(), Map.copyOf(p.getAttributes()));
    }
}
