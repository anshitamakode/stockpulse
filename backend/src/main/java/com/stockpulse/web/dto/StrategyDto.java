package com.stockpulse.web.dto;

import java.util.List;
import java.util.Set;

/** @param llmConfigured false when no API key is set; the AI strategy will then always fall back */
public record StrategyDto(String active, Set<String> available, String llmProvider, String llmModel,
                          List<String> llmFallbackModels, boolean llmConfigured) {}
