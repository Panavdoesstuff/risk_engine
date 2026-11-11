package riskengine.margin;

/**
 * A square-off episode started or ended. An account has at most one open episode.
 *
 * @param mode   AUTO: the engine started it because the account stayed over the square-off threshold;
 *               MANUAL: risk-ops asked for the account to be closed out
 * @param actor  {@code engine}, or the risk-ops user for a manual episode
 * @param legs   legs planned so far in this episode
 */
public record EpisodeChange(String accountId, String episodeId, AccountRisk.Mode mode, State state, String actor,
                            int legs, long tickSeq, long logTimeMillis) implements EngineOutput {
    public enum State {
        OPENED,
        /** AUTO: utilisation is back under the margin-call threshold. MANUAL: the account is flat. */
        RESOLVED,
        /** Still over the limit, and nothing left whose closing would free margin. */
        EXHAUSTED
    }
}
