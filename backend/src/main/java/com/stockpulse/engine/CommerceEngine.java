package com.stockpulse.engine;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds the active {@link CommerceStrategy}. Switching is a single atomic reference swap, so it
 * takes effect for the next recommendation without a restart and without locking callers.
 * New strategies are picked up automatically by being Spring beans.
 */
@Component
public class CommerceEngine {

    private static final Logger log = LoggerFactory.getLogger(CommerceEngine.class);

    private final Map<String, CommerceStrategy> strategies;
    private final AtomicReference<CommerceStrategy> active;

    public CommerceEngine(List<CommerceStrategy> available, StockPulseProperties props) {
        this.strategies = available.stream().collect(Collectors.toUnmodifiableMap(CommerceStrategy::name, Function.identity()));
        CommerceStrategy initial = strategies.get(props.strategy());
        if (initial == null) {
            throw new IllegalStateException("stockpulse.strategy '" + props.strategy() + "' is not one of " + strategies.keySet());
        }
        this.active = new AtomicReference<>(initial);
        log.info("Commerce strategy at startup: {}", initial.name());
    }

    public CommerceRecommendation recommend(ProductContext context) {
        return active.get().recommend(context);
    }

    public String activeName() {
        return active.get().name();
    }

    public Set<String> available() {
        return strategies.keySet();
    }

    public String switchTo(String name) {
        CommerceStrategy next = strategies.get(name);
        if (next == null) {
            throw new BadRequestException("Unknown strategy '" + name + "'. Available: " + strategies.keySet());
        }
        CommerceStrategy previous = active.getAndSet(next);
        log.info("Commerce strategy switched {} -> {}", previous.name(), next.name());
        return next.name();
    }
}
