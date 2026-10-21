package com.stockpulse.repository;

import com.stockpulse.domain.InventorySnapshot;
import com.stockpulse.domain.SnapshotReason;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InventorySnapshotRepository extends JpaRepository<InventorySnapshot, UUID> {

    List<InventorySnapshot> findByProductIdOrderByCapturedAtDesc(String productId, Pageable pageable);

    /** Units sold (as a positive number) for a product since the given instant. */
    @Query("""
            select coalesce(sum(-s.quantityDelta), 0L) from InventorySnapshot s
            where s.product.id = :productId and s.reason = :reason and s.capturedAt >= :since
            """)
    long sumUnitsSince(@Param("productId") String productId,
                       @Param("reason") SnapshotReason reason,
                       @Param("since") Instant since);
}
