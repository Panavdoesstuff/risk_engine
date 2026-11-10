package riskengine.margin;

/**
 * One account's margin at one moment. Rupees, unrounded (round only for display and storage).
 *
 * <pre>
 *   worstScenarioLoss  = max(0, max over the 16 scenarios of portfolio loss)
 *   shortOptionMinimum = rate x underlying notional of short options
 *   spanRequirement    = max(shortOptionMinimum, worstScenarioLoss)
 *   netOptionValue     = value of long options - value of short options
 *   riskRequirement    = max(0, spanRequirement - netOptionValue)
 *   exposureMargin     = rate x notional of futures and short options
 *   required           = max(0, riskRequirement + exposureMargin)
 *   futuresMtm         = unrealised profit (+) / loss (-) on futures since entry
 *   available          = cash + collateral + futuresMtm
 *   utilisation        = required / available
 * </pre>
 *
 * Subtracting net option value is how a long option's loss is capped at its premium: a long-only account's
 * worst loss can never exceed the value of its options, so its risk requirement is zero (it already paid).
 * For a short option it adds the cost of buying the option back.
 *
 * <p>Futures have no premium, so their running profit or loss has to be counted somewhere: the exchange
 * settles it in cash every evening, and intraday we count the not-yet-settled amount as if it were cash.
 *
 * @param worstScenario 1-based scenario number (1-16) that produced {@code worstScenarioLoss}; 0 if no loss
 */
public record MarginResult(
        double worstScenarioLoss,
        int worstScenario,
        double shortOptionMinimum,
        double spanRequirement,
        double netOptionValue,
        double riskRequirement,
        double exposureMargin,
        double required,
        double futuresMtm,
        double available,
        double utilisation) {}
