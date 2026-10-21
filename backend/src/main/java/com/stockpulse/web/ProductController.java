package com.stockpulse.web;

import com.stockpulse.service.InventoryService;
import com.stockpulse.service.SuggestionService;
import com.stockpulse.web.dto.OrderResultDto;
import com.stockpulse.web.dto.ProductDto;
import com.stockpulse.web.dto.QuantityRequest;
import com.stockpulse.web.dto.SnapshotDto;
import com.stockpulse.web.dto.SuggestionDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final InventoryService inventory;
    private final SuggestionService suggestions;

    public ProductController(InventoryService inventory, SuggestionService suggestions) {
        this.inventory = inventory;
        this.suggestions = suggestions;
    }

    @GetMapping
    public List<ProductDto> list() {
        return inventory.listProducts();
    }

    @GetMapping("/{id}")
    public ProductDto get(@PathVariable String id) {
        return inventory.getProduct(id);
    }

    @GetMapping("/{id}/snapshots")
    public List<SnapshotDto> snapshots(@PathVariable String id, @RequestParam(defaultValue = "20") int limit) {
        return inventory.history(id, limit);
    }

    /** Simulates a sale. May open suggestions automatically; see the response's {@code opened}. */
    @PostMapping("/{id}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResultDto order(@PathVariable String id, @Valid @RequestBody QuantityRequest body) {
        return inventory.placeOrder(id, body.quantity());
    }

    /** Simulates a delivery arriving (what an accepted reorder turns into until sprint 2 adds POs). */
    @PostMapping("/{id}/restock")
    public ProductDto restock(@PathVariable String id, @Valid @RequestBody QuantityRequest body) {
        return inventory.restock(id, body.quantity());
    }

    /** Manual "ask the advisor". Returns PROCESSING suggestions; poll /suggestions for the result. */
    @PostMapping("/{id}/suggestions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<SuggestionDto> requestSuggestions(@PathVariable String id) {
        return suggestions.requestManual(id);
    }
}
