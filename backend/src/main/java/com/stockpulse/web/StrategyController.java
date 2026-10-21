package com.stockpulse.web;

import com.stockpulse.ai.LLMGateway;
import com.stockpulse.engine.CommerceEngine;
import com.stockpulse.web.dto.StrategyDto;
import com.stockpulse.web.dto.StrategyRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime strategy switch: takes effect for the next suggestion, no restart. */
@RestController
@RequestMapping("/strategy")
public class StrategyController {

    private final CommerceEngine engine;
    private final LLMGateway llm;

    public StrategyController(CommerceEngine engine, LLMGateway llm) {
        this.engine = engine;
        this.llm = llm;
    }

    @GetMapping
    public StrategyDto get() {
        return dto();
    }

    @PatchMapping
    public StrategyDto set(@Valid @RequestBody StrategyRequest body) {
        engine.switchTo(body.strategy().trim());
        return dto();
    }

    private StrategyDto dto() {
        return new StrategyDto(engine.activeName(), engine.available(), llm.provider(), llm.model(), llm.fallbackModels(),
                llm.isConfigured());
    }
}
