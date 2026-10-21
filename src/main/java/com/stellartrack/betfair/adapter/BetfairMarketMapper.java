package com.stellartrack.betfair.adapter;

import com.stellartrack.betfair.api.BettingTypes.MarketBook;
import com.stellartrack.betfair.api.BettingTypes.MarketCatalogue;
import com.stellartrack.betfair.api.BettingTypes.PriceSize;
import com.stellartrack.betfair.api.BettingTypes.Runner;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Translates raw Betfair market types into provider-neutral application models. */
final class BetfairMarketMapper {

    private BetfairMarketMapper() {
    }

    static Optional<MarketSummary> toSummary(MarketCatalogue catalogue) {
        String typeCode = catalogue.description() != null ? catalogue.description().marketType() : null;
        Optional<MarketType> type = MarketType.fromBetfairCode(typeCode);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        var event = catalogue.event();
        List<MarketSummary.RunnerInfo> runners = catalogue.runners() == null ? List.of()
                : catalogue.runners().stream()
                        .map(r -> new MarketSummary.RunnerInfo(r.selectionId(), r.runnerName(),
                                r.handicap() == null ? BigDecimal.ZERO : r.handicap(), r.sortPriority(), r.metadata()))
                        .toList();
        return Optional.of(new MarketSummary(
                catalogue.marketId(),
                catalogue.marketName(),
                type.get(),
                catalogue.marketStartTime(),
                event != null ? event.id() : null,
                event != null ? event.name() : null,
                event != null ? event.venue() : null,
                event != null ? event.countryCode() : null,
                catalogue.description() != null ? catalogue.description().raceType() : null,
                catalogue.totalMatched(),
                runners,
                catalogue.description() != null ? catalogue.description().marketBaseRate() : null,
                catalogue.description() != null ? catalogue.description().eachWayDivisor() : null));
    }

    static MarketPrices toPrices(MarketBook book, Instant asOf) {
        List<MarketPrices.RunnerPrices> runners = book.runners() == null ? List.of()
                : book.runners().stream().map(BetfairMarketMapper::toRunner).toList();
        return new MarketPrices(book.marketId(), book.status(), Boolean.TRUE.equals(book.inplay()),
                Boolean.TRUE.equals(book.isMarketDataDelayed()), book.betDelay(), book.numberOfWinners(),
                book.numberOfActiveRunners(), book.totalMatched(), book.version(), asOf, MarketPrices.PriceSource.POLL,
                runners);
    }

    private static MarketPrices.RunnerPrices toRunner(Runner runner) {
        var ex = runner.ex();
        return new MarketPrices.RunnerPrices(runner.selectionId(), runner.status(), runner.adjustmentFactor(),
                runner.lastPriceTraded(), runner.totalMatched(),
                ex == null ? List.of() : levels(ex.availableToBack()),
                ex == null ? List.of() : levels(ex.availableToLay()),
                runner.removalDate());
    }

    private static List<MarketPrices.PriceLevel> levels(List<PriceSize> sizes) {
        if (sizes == null) {
            return Collections.emptyList();
        }
        return sizes.stream().map(ps -> new MarketPrices.PriceLevel(ps.price(), ps.size())).toList();
    }
}
