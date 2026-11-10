package riskengine.shard;

import java.nio.charset.StandardCharsets;

/**
 * Which partition (= shard) owns an account: {@code positive(murmur2(utf8(accountId))) % N}.
 *
 * <p>This is the same function Kafka's default partitioner applies to a record key, so in M4 producing
 * with key = accountId lands on the same partition this class predicts (a test checks it against the
 * Kafka client library). Changing N re-routes accounts, so N is fixed for the life of a log.
 */
public final class Partitioner {
    private Partitioner() {}

    public static int partition(String accountId, int partitions) {
        if (partitions <= 0) throw new IllegalArgumentException("partitions must be positive");
        return (murmur2(accountId.getBytes(StandardCharsets.UTF_8)) & 0x7fffffff) % partitions;
    }

    /** MurmurHash2, 32-bit, seed 0x9747b28c (the variant Kafka uses). */
    static int murmur2(byte[] data) {
        int length = data.length;
        final int m = 0x5bd1e995;
        final int r = 24;
        int h = 0x9747b28c ^ length;
        int blocks = length / 4;
        for (int i = 0; i < blocks; i++) {
            int i4 = i * 4;
            int k = (data[i4] & 0xff) + ((data[i4 + 1] & 0xff) << 8) + ((data[i4 + 2] & 0xff) << 16) + ((data[i4 + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }
        int tail = length & ~3;
        int rest = length % 4;
        if (rest == 3) h ^= (data[tail + 2] & 0xff) << 16;
        if (rest >= 2) h ^= (data[tail + 1] & 0xff) << 8;
        if (rest >= 1) {
            h ^= data[tail] & 0xff;
            h *= m;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h;
    }
}
