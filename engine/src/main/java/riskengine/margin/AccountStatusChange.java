package riskengine.margin;

/**
 * The account's status (accounts.status) changed.
 *
 * @param cause why, in a word: the event that moved it
 * @param actor {@code engine}, or the risk-ops user whose instruction caused it
 */
public record AccountStatusChange(String accountId, AccountRisk.Status from, AccountRisk.Status to, String cause, String actor,
                                  long tickSeq, long logTimeMillis) implements EngineOutput {}
