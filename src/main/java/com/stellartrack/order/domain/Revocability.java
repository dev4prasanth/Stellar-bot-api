package com.stellartrack.order.domain;

import java.math.BigDecimal;

/**
 * Whether a bet's unmatched stake can still be revoked (cancelled on Betfair), and why not. Betfair cancels only the
 * unmatched part of an order: an unmatched bet is revoked in full, a partially matched bet only for its remainder, and
 * a fully matched, cancelled, lapsed, voided or settled bet cannot be revoked.
 *
 * @param revocable true while an unmatched amount remains on an executable order
 * @param reason    why it cannot be revoked (shown as the disabled button's tooltip); null when revocable
 */
public record Revocability(boolean revocable, String reason) {

    private static final String PREFIX = "This bet cannot be revoked because ";

    public static Revocability yes() {
        return new Revocability(true, null);
    }

    public static Revocability no(String why) {
        return new Revocability(false, PREFIX + why);
    }

    /**
     * Revocability of a real (Betfair) bet from its stored state.
     *
     * @param intentStatus    submission status of the order intention
     * @param betStatus       Betfair order status (EXECUTABLE / EXECUTION_COMPLETE), null when there is no Betfair bet
     * @param settlementClass reporting class of its current settlement, null while unsettled
     */
    public static Revocability ofOrder(String intentStatus, String betStatus, BigDecimal sizeMatched,
            BigDecimal sizeRemaining, BigDecimal sizeCancelled, BigDecimal sizeLapsed, BigDecimal sizeVoided,
            String settlementClass) {
        if (settlementClass != null) {
            return switch (settlementClass) {
                case "VOID" -> no("it has been voided by Betfair.");
                case "UNMATCHED" -> no("its stake was cancelled or lapsed before it matched.");
                default -> no("it has already been settled.");
            };
        }
        if (betStatus == null) {
            return OrderIntentStatus.LIVE.stream().anyMatch(s -> s.name().equals(intentStatus))
                    ? no("its placement on Betfair is still being confirmed.")
                    : no("it was never placed on Betfair.");
        }
        if ("EXECUTABLE".equals(betStatus) && positive(sizeRemaining)) {
            return yes();
        }
        if (positive(sizeMatched)) {
            return positive(sizeCancelled) || positive(sizeLapsed) || positive(sizeVoided)
                    ? no("no unmatched stake is left and Betfair does not cancel matched stakes.")
                    : no("it has already been fully matched.");
        }
        if (positive(sizeVoided)) {
            return no("it has been voided by Betfair.");
        }
        if (positive(sizeLapsed)) {
            return no("its stake has lapsed.");
        }
        return no("it has already been cancelled.");
    }

    /**
     * Revocability of a simulated Watch bet from its display status (see {@code v_watch_bet.display_status}).
     */
    public static Revocability ofWatchBet(String displayStatus) {
        return switch (displayStatus) {
            case "PENDING", "PARTIALLY_MATCHED" -> yes();
            case "MATCHED" -> no("it has already been fully matched.");
            case "UNMATCHED" -> no("its stake was cancelled or lapsed before it matched.");
            case "VOID" -> no("it has been voided.");
            case "UNRESOLVED" -> no("its matching has ended and it is awaiting the race result.");
            default -> no("it has already been settled.");
        };
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
