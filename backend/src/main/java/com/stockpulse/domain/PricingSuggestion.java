package com.stockpulse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "pricing_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class PricingSuggestion extends Suggestion {

    /** Price when the suggestion was opened; accepting is refused if the live price has moved since. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal recommendedPrice;

    @Enumerated(EnumType.STRING)
    private PricingAction action;
}
