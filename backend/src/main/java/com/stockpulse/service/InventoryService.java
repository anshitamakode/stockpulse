package com.stockpulse.service;

import com.stockpulse.domain.InventorySnapshot;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.SnapshotReason;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.exception.ConflictException;
import com.stockpulse.exception.NotFoundException;
import com.stockpulse.repository.InventorySnapshotRepository;
import com.stockpulse.repository.ProductRepository;
import com.stockpulse.web.dto.OrderResultDto;
import com.stockpulse.web.dto.ProductDto;
import com.stockpulse.web.dto.SnapshotDto;
import com.stockpulse.web.dto.SuggestionDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Inventory changes: orders reduce stock, restocks add it. An order is where the agentic loop
 * starts — the same transaction that records the sale detects the signal and opens suggestions,
 * so a sale and its follow-up advice are never out of step. Concurrent orders on one product are
 * serialised by the product's {@code @Version} (the loser gets a 409 and can retry).
 */
@Service
public class InventoryService {

    private final ProductRepository products;
    private final InventorySnapshotRepository snapshots;
    private final DemandTracker demand;
    private final TriggerDetector detector;
    private final SuggestionService suggestions;
    private final Clock clock;

    public InventoryService(ProductRepository products, InventorySnapshotRepository snapshots, DemandTracker demand,
                            TriggerDetector detector, SuggestionService suggestions, Clock clock) {
        this.products = products;
        this.snapshots = snapshots;
        this.demand = demand;
        this.detector = detector;
        this.suggestions = suggestions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductDto> listProducts() {
        return products.findAllByOrderByIdAsc().stream().map(ProductDto::from).toList();
    }

    @Transactional(readOnly = true)
    public ProductDto getProduct(String id) {
        return ProductDto.from(find(id));
    }

    @Transactional(readOnly = true)
    public List<SnapshotDto> history(String id, int limit) {
        find(id);
        return snapshots.findByProductIdOrderByCapturedAtDesc(id, PageRequest.of(0, Math.min(Math.max(limit, 1), 200)))
                .stream().map(SnapshotDto::from).toList();
    }

    @Transactional
    public OrderResultDto placeOrder(String productId, int quantity) {
        Product product = find(productId);
        if (quantity > product.getStockLevel()) {
            throw new ConflictException("Only " + product.getStockLevel() + " units of " + productId
                    + " in stock; cannot sell " + quantity);
        }
        product.setStockLevel(product.getStockLevel() - quantity);
        snapshots.save(InventorySnapshot.of(product, -quantity, SnapshotReason.ORDER, clock.instant()));

        long unitsInWindow = demand.refreshVelocity(product);   // query auto-flushes the snapshot above
        Optional<TriggerReason> trigger = detector.detect(product, unitsInWindow);
        List<SuggestionDto> opened = trigger.map(t -> suggestions.open(product, t)).orElse(List.of());
        suggestions.refreshProductStatus(product);

        return new OrderResultDto(ProductDto.from(product), unitsInWindow, trigger.orElse(null), opened);
    }

    @Transactional
    public ProductDto restock(String productId, int quantity) {
        Product product = find(productId);
        product.setStockLevel(product.getStockLevel() + quantity);
        snapshots.save(InventorySnapshot.of(product, quantity, SnapshotReason.RESTOCK, clock.instant()));
        suggestions.refreshProductStatus(product);
        return ProductDto.from(product);
    }

    private Product find(String id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
    }
}
