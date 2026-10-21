package com.stockpulse.ai;

/**
 * A successful model reply plus where it came from.
 *
 * @param model     the model that actually answered (may be a fallback model)
 * @param latencyMs time for the successful attempt only
 * @param attempts  total HTTP attempts made, including failed retries and other models
 */
public record LlmResponse(String text, String model, long latencyMs, int attempts) {}
