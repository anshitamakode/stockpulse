package com.stockpulse.web.dto;

import com.stockpulse.domain.SuggestionStatus;
import jakarta.validation.constraints.NotNull;

/** {@code status} must be ACCEPTED or REJECTED; anything else is a 400. */
public record DecisionRequest(@NotNull SuggestionStatus status) {}
