package riskengine.shard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Test;

class PartitionerTest {

    @Test
    void matchesKafkasDefaultKeyPartitioning() {
        for (int n : new int[] {1, 2, 4, 7, 16}) {
            for (int a = 0; a < 20_000; a++) {
                String id = a % 3 == 0 ? String.format("A%07d", a) : a % 3 == 1 ? "acct-" + a : "é" + a + "x";
                int kafka = Utils.toPositive(Utils.murmur2(id.getBytes(StandardCharsets.UTF_8))) % n;
                assertEquals(kafka, Partitioner.partition(id, n), id + " over " + n);
            }
        }
    }

    @Test
    void spreadsSequentialAccountIdsEvenly() {
        int n = 4, total = 100_000;
        int[] counts = new int[n];
        for (int a = 1; a <= total; a++) counts[Partitioner.partition(String.format("A%07d", a), n)]++;
        for (int c : counts) assertTrue(Math.abs(c - total / n) < total / n * 0.03, java.util.Arrays.toString(counts));
    }
}
