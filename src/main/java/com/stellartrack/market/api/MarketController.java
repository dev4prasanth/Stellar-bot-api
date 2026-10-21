package com.stellartrack.market.api;

import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.application.MarketCatalogService;
import com.stellartrack.market.application.MarketQueryService;
import com.stellartrack.market.application.MarketQueryService.FilterOptions;
import com.stellartrack.market.application.MarketQueryService.MarketDetail;
import com.stellartrack.market.application.MarketQueryService.MarketListItem;
import com.stellartrack.market.application.MarketQueryService.MarketListQuery;
import com.stellartrack.market.domain.RacingEvent;
import com.stellartrack.market.filter.FilterField;
import com.stellartrack.market.filter.RunnerFilterCriteria;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.MARKETS)
@Tag(name = "Markets")
public class MarketController {

    public record FilterFieldInfo(FilterField field, String label, FilterField.DataSource source, String sourceField) {
    }

    public record EvaluateRequest(BetSide side, @Valid RunnerFilterCriteria criteria) {
    }

    private final MarketQueryService queryService;
    private final MarketCatalogService catalogService;
    private final Clock clock;

    public MarketController(MarketQueryService queryService, MarketCatalogService catalogService, Clock clock) {
        this.queryService = queryService;
        this.catalogService = catalogService;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "List cached horse-racing WIN/PLACE markets with basic filters")
    public List<MarketListItem> list(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Set<String> countries,
            @RequestParam(required = false) Set<String> venues,
            @RequestParam(required = false) Set<MarketType> marketTypes,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean inPlay,
            @RequestParam(required = false) String recommendationStatus) {
        Instant start = from != null ? from : clock.instant().minus(Duration.ofHours(1));
        Instant end = to != null ? to : start.plus(Duration.ofHours(24));
        return queryService.list(new MarketListQuery(start, end, countries, venues, marketTypes, status, inPlay,
                recommendationStatus));
    }

    @GetMapping("/filter-options")
    @Operation(summary = "Countries, venues and race types available in the cached catalogue")
    public FilterOptions filterOptions(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant start = from != null ? from : clock.instant().minus(Duration.ofHours(1));
        return queryService.filterOptions(start, to != null ? to : start.plus(Duration.ofDays(2)));
    }

    @GetMapping("/filter-fields")
    @Operation(summary = "Advanced filter fields with the verified data source of each value")
    public List<FilterFieldInfo> filterFields() {
        return Arrays.stream(FilterField.values())
                .map(f -> new FilterFieldInfo(f, f.label(), f.source(), f.sourceField()))
                .toList();
    }

    @GetMapping("/events")
    @Operation(summary = "Live horse-racing events from Betfair listEvents")
    public List<RacingEvent> events(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant start = from != null ? from : clock.instant();
        return queryService.events(start, to != null ? to : start.plus(Duration.ofDays(1)));
    }

    @PostMapping("/sync")
    @Operation(summary = "Refresh the cached catalogue from Betfair now")
    public void sync() {
        catalogService.syncUpcoming();
    }

    @GetMapping("/{marketId}")
    @Operation(summary = "Market detail with live prices, freshness and price ranks")
    public MarketDetail detail(@PathVariable String marketId, @RequestParam(required = false) BetSide side) {
        return queryService.detail(marketId, side, RunnerFilterCriteria.none());
    }

    @PostMapping("/{marketId}/evaluate")
    @Operation(summary = "Apply advanced runner filters to a market and explain each exclusion")
    public MarketDetail evaluate(@PathVariable String marketId, @Valid @RequestBody EvaluateRequest request) {
        return queryService.detail(marketId, request.side(), request.criteria());
    }
}
