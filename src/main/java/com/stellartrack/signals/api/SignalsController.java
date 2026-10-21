package com.stellartrack.signals.api;

import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.signals.application.SignalQueryService;
import com.stellartrack.signals.application.SignalViews.CardDetail;
import com.stellartrack.signals.application.SignalViews.CardSummary;
import com.stellartrack.signals.application.SignalViews.RaceDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the accepted Layer 1/2/3 messages. These are inputs only; nothing here proposes or places bets. */
@RestController
@RequestMapping(ApiPaths.SIGNALS)
@Tag(name = "Race signals (Layer 1/2/3)")
public class SignalsController {

    private final SignalQueryService signals;

    public SignalsController(SignalQueryService signals) {
        this.signals = signals;
    }

    @GetMapping("/cards")
    @Operation(summary = "Race cards with per-layer coverage, IN/OUT planets and scenario")
    public List<CardSummary> cards(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String venueId) {
        return signals.cards(date, venueId);
    }

    @GetMapping("/cards/{cardId}")
    @Operation(summary = "Card context and per-race Layer 1/2/3 status (RECEIVED, WAITING, NOT_EXPECTED)")
    public CardDetail card(@PathVariable String cardId) {
        return signals.card(cardId);
    }

    @GetMapping("/cards/{cardId}/races/{raceNumber}")
    @Operation(summary = "Layer 1 prediction, Layer 2 states from race N-1, Layer 3 rankings and a per-runner merge")
    public RaceDetail race(@PathVariable String cardId, @PathVariable int raceNumber) {
        return signals.race(cardId, raceNumber);
    }
}
