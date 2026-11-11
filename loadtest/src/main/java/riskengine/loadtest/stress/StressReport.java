package riskengine.loadtest.stress;

import java.util.List;
import java.util.SortedMap;

/**
 * @param records            log records written (a tick counts once per partition)
 * @param byType             records by event type
 * @param outputs            engine outputs compared, one by one, against the reference engine
 * @param decisions          order decisions by reason (OK = accepted)
 * @param outputMismatches   outputs that differ between a shard and the reference
 * @param stateMismatches    accounts whose final positions, cash, level, reservations or margin differ
 * @param replayMismatches   shards whose state hash, after replaying the recorded log on one thread, differs
 *                           from the hash the live, concurrent run ended with
 * @param maxLag             most records any shard was behind its partition's end while the writers ran
 * @param liveSeconds        from the first write until every shard had applied everything
 * @param referenceSeconds   the single-threaded reference run over the same records
 * @param examples           the first few mismatches, in words
 */
public record StressReport(long records, SortedMap<String, Long> byType, long outputs, SortedMap<String, Long> decisions,
                           long levelChanges, long releases, long duplicateFills, long duplicateOrders,
                           long outputMismatches, long stateMismatches, long replayMismatches,
                           long maxLag, double liveSeconds, double referenceSeconds, List<String> examples) {

    public long mismatches() {
        return outputMismatches + stateMismatches + replayMismatches;
    }

    public String summary() {
        return String.format("""
                records written        %,d  %s
                outputs compared       %,d  (order decisions %s; %,d level changes; %,d reservations released by a close)
                duplicates ignored     %,d fills, %,d orders
                live run               %.1f s  (%,.0f records/s through the shards; max shard lag %,d records)
                reference run          %.1f s  (single thread)
                MISMATCHES             %,d  (outputs %,d, final account state %,d, replay hash %,d)%s""",
                records, byType, outputs, decisions, levelChanges, releases, duplicateFills, duplicateOrders,
                liveSeconds, records / liveSeconds, maxLag, referenceSeconds,
                mismatches(), outputMismatches, stateMismatches, replayMismatches,
                examples.isEmpty() ? "" : "\n  " + String.join("\n  ", examples));
    }
}
