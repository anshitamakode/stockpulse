package com.stockpulse.service;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.InventorySnapshot;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.SnapshotReason;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.engine.ProductContext;
import com.stockpulse.repository.InventorySnapshotRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Rolling demand math and the bridge from entities to the engine's immutable {@link ProductContext}.
 * <p>Velocity model (units/day): {@code baseline + units sold in the last windowMinutes}. Crude but
 * deterministic and easy to demo; a real forecast is a drop-in replacement behind this class.
 */
@Component
public class DemandTracker {

    private static final int HISTORY_SIZE = 6;

    private final InventorySnapshotRepository snapshots;
    private final StockPulseProperties.Demand demand;
    private final Clock clock;

    public DemandTracker(InventorySnapshotRepository snapshots, StockPulseProperties props, Clock clock) {
        this.snapshots = snapshots;
        this.demand = props.demand();
        this.clock = clock;
    }

    public long unitsSoldInWindow(String productId) {
        Instant since = clock.instant().minus(Duration.ofMinutes(demand.windowMinutes()));
        return snapshots.sumUnitsSince(productId, SnapshotReason.ORDER, since);
    }

    /** Recomputes and stores the product's velocity; returns the units sold in the window. */
    public long refreshVelocity(Product product) {
        long units = unitsSoldInWindow(product.getId());
        product.setDemandVelocity((int) Math.min(Integer.MAX_VALUE, product.getBaselineVelocity() + units));
        return units;
    }

    public ProductContext contextFor(Product p, TriggerReason trigger) {
        List<ProductContext.HistoryPoint> history = snapshots
                .findByProductIdOrderByCapturedAtDesc(p.getId(), PageRequest.of(0, HISTORY_SIZE)).stream()
                .map(DemandTracker::point)
                .toList();
        return new ProductContext(p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getCurrentPrice(),
                p.getStockLevel(), p.getReorderThreshold(), p.getDemandVelocity(), p.getBaselineVelocity(),
                unitsSoldInWindow(p.getId()), demand.windowMinutes(), trigger, p.getAttributes(), history);
    }

    private static ProductContext.HistoryPoint point(InventorySnapshot s) {
        return new ProductContext.HistoryPoint(s.getReason().name(), s.getQuantityDelta(), s.getStockLevel(),
                s.getCapturedAt().toString());
    }
}
