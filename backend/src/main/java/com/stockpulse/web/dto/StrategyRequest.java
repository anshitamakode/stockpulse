package com.stockpulse.web.dto;

import jakarta.validation.constraints.NotBlank;

public record StrategyRequest(@NotBlank String strategy) {}
