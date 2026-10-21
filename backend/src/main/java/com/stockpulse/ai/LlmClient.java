package com.stockpulse.ai;

/**
 * The only thing the commerce engine knows about LLMs. Keeping it this narrow lets the AI
 * strategy be unit-tested with a stub and lets providers change without touching business code.
 */
public interface LlmClient {

    /**
     * @return the reply and the model that produced it
     * @throws LLMGateway.LLMException when every attempt (retries and fallback models) failed
     */
    LlmResponse complete(String systemPrompt, String userPrompt);
}
