package com.stockpulse.web;

import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.service.SuggestionService;
import com.stockpulse.web.dto.DecisionRequest;
import com.stockpulse.web.dto.SuggestionDto;
import com.stockpulse.web.dto.SuggestionKind;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/suggestions")
public class SuggestionController {

    private final SuggestionService suggestions;

    public SuggestionController(SuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    /** {@code ?status=PENDING,PROCESSING} filters; no parameter returns everything, newest first. */
    @GetMapping
    public List<SuggestionDto> list(@RequestParam(required = false) List<SuggestionStatus> status) {
        return suggestions.list(status);
    }

    /** {@code kind} is {@code pricing} or {@code reorder}; body {@code {"status":"ACCEPTED"|"REJECTED"}}. */
    @PatchMapping("/{kind}/{id}")
    public SuggestionDto decide(@PathVariable String kind, @PathVariable UUID id,
                                @Valid @RequestBody DecisionRequest body) {
        return suggestions.decide(parseKind(kind), id, body.status());
    }

    private static SuggestionKind parseKind(String kind) {
        try {
            return SuggestionKind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.stockpulse.exception.BadRequestException("kind must be 'pricing' or 'reorder'");
        }
    }
}
