package com.stockpulse.bootstrap;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.InventorySnapshot;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.ProductStatus;
import com.stockpulse.domain.SnapshotReason;
import com.stockpulse.repository.InventorySnapshotRepository;
import com.stockpulse.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Seeds the eight products from the brief (Addendum A). Skips if any product exists.
 * <ul>
 *   <li>PRD-003 T-Shirt: stock 8 &lt; threshold 15 — one order fires the low-stock loop.</li>
 *   <li>PRD-008 Hoodie: stock 11 &lt; 12, baseline velocity 15 — the first order opens low-stock
 *       suggestions; a burst of orders then fires a demand spike that supersedes them.</li>
 * </ul>
 */
@Component
public class DemoDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final ProductRepository products;
    private final InventorySnapshotRepository snapshots;
    private final Clock clock;

    public DemoDataInitializer(ProductRepository products, InventorySnapshotRepository snapshots, Clock clock) {
        this.products = products;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (products.count() > 0) {
            log.info("Products already present, skipping seed");
            return;
        }
        List<Product> seed = List.of(
                p("PRD-001", "SKU-ELEC-001", "Wireless Earbuds Pro", Category.ELECTRONICS, "79.99", 45, 20, 3, ProductStatus.ACTIVE),
                p("PRD-002", "SKU-ELEC-002", "USB-C Hub 7-Port", Category.ELECTRONICS, "34.99", 120, 30, 1, ProductStatus.ACTIVE),
                p("PRD-003", "SKU-APP-001", "Organic Cotton T-Shirt", Category.APPAREL, "24.99", 8, 15, 12, ProductStatus.PRICE_REVIEW_PENDING),
                p("PRD-004", "SKU-APP-002", "Running Shorts — Navy", Category.APPAREL, "39.99", 55, 20, 2, ProductStatus.ACTIVE),
                p("PRD-005", "SKU-HOME-001", "Ceramic Pour-Over Set", Category.HOME, "49.99", 22, 10, 4, ProductStatus.ACTIVE),
                p("PRD-006", "SKU-HOME-002", "LED Desk Lamp — Dimmable", Category.HOME, "59.99", 0, 15, 0, ProductStatus.OUT_OF_STOCK),
                p("PRD-007", "SKU-ELEC-003", "Portable Charger 20K", Category.ELECTRONICS, "44.99", 18, 25, 8, ProductStatus.ACTIVE),
                p("PRD-008", "SKU-APP-003", "Hoodie — Heather Grey", Category.APPAREL, "54.99", 11, 12, 15, ProductStatus.ACTIVE));

        products.saveAll(seed);
        Instant now = clock.instant();
        seed.forEach(product -> snapshots.save(InventorySnapshot.of(product, 0, SnapshotReason.SEED, now)));
        log.info("Seeded {} products", seed.size());
    }

    private static Product p(String id, String sku, String name, Category category, String price,
                             int stock, int threshold, int velocity, ProductStatus status) {
        return new Product(id, sku, name, category, new BigDecimal(price), stock, threshold, velocity, status);
    }
}
