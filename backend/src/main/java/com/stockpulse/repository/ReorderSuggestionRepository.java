package com.stockpulse.repository;

import com.stockpulse.domain.ReorderSuggestion;
import com.stockpulse.domain.SuggestionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReorderSuggestionRepository extends JpaRepository<ReorderSuggestion, UUID> {

    boolean existsByProductIdAndStatusIn(String productId, Collection<SuggestionStatus> statuses);

    List<ReorderSuggestion> findByProductIdAndStatusIn(String productId, Collection<SuggestionStatus> statuses);

    @EntityGraph(attributePaths = "product")
    List<ReorderSuggestion> findByStatusInOrderByCreatedAtDesc(Collection<SuggestionStatus> statuses);

    @EntityGraph(attributePaths = "product")
    List<ReorderSuggestion> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "product")
    List<ReorderSuggestion> findByStatusAndCreatedAtBefore(SuggestionStatus status, Instant before);
}
