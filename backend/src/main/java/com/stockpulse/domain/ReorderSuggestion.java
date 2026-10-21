package com.stockpulse.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reorder_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class ReorderSuggestion extends Suggestion {

    /** Stock level when the suggestion was opened, for context in the console. */
    private int stockAtRequest;

    private Integer recommendedQuantity;
}
