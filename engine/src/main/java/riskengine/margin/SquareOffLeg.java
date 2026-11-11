package riskengine.margin;

import java.math.BigDecimal;

/**
 * One closing order of a square-off: the engine's instruction to the broker side.
 *
 * @param legKey          {@code accountId:episodeId:instrumentId:seq}. It names this leg for ever: it is the
 *                        order id, the outbox idempotency key and the key the broker dedupes on, so however
 *                        many times the leg is published, stored or sent, it is executed once.
 * @param qty             signed closing quantity: + buys back a short, - sells a long
 * @param mark            the engine's price for the instrument when the leg was planned
 * @param expectedRelease required margin this leg is expected to free, at that mark
 */
public record SquareOffLeg(String legKey, String accountId, String episodeId, String instrumentId, int seq, long qty,
                           BigDecimal mark, double expectedRelease, long tickSeq, long logTimeMillis) implements EngineOutput {

    public static String key(String accountId, String episodeId, String instrumentId, int seq) {
        return accountId + ":" + episodeId + ":" + instrumentId + ":" + seq;
    }
}
