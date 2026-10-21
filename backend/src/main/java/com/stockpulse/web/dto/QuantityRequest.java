package com.stockpulse.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record QuantityRequest(@NotNull @Min(1) @Max(10_000) Integer quantity) {}
