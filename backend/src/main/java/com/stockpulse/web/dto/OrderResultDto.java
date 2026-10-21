package com.stockpulse.web.dto;

import com.stockpulse.domain.TriggerReason;

import java.util.List;

/**
 * @param triggerDetected the signal the order produced, or null
 * @param opened          suggestions this order opened (empty when de-duplicated against open ones)
 */
public record OrderResultDto(ProductDto product, long unitsSoldInWindow, TriggerReason triggerDetected,
                             List<SuggestionDto> opened) {}
