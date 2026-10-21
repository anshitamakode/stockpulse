package com.stockpulse.repository;

import com.stockpulse.domain.PricingSuggestion;
import com.stockpulse.domain.SuggestionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PricingSuggestionRepository extends JpaRepository<PricingSuggestion, UUID> {

    boolean existsByProductIdAndStatusIn(String productId, Collection<SuggestionStatus> statuses);

    List<PricingSuggestion> findByProductIdAndStatusIn(String productId, Collection<SuggestionStatus> statuses);

    @EntityGraph(attributePaths = "product")
    List<PricingSuggestion> findByStatusInOrderByCreatedAtDesc(Collection<SuggestionStatus> statuses);

    @EntityGraph(attributePaths = "product")
    List<PricingSuggestion> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "product")
    List<PricingSuggestion> findByStatusAndCreatedAtBefore(SuggestionStatus status, Instant before);
}
