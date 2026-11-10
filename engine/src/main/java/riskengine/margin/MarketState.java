package riskengine.margin;

/**
 * What the market looks like for one underlying at one log time.
 *
 * @param spot          index level
 * @param vol           annual volatility (Nifty: VIX / 100)
 * @param logTimeMillis event time, used for time to expiry
 */
public record MarketState(String underlying, double spot, double vol, long logTimeMillis) {}
