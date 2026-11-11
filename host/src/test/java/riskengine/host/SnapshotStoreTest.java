package riskengine.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import riskengine.margin.AccountBook;
import riskengine.shard.Fixtures;
import riskengine.shard.ShardConfig;
import riskengine.shard.ShardState;

class SnapshotStoreTest {
    static ShardState shard(int id) {
        List<AccountBook> books = Fixtures.books(50, id + 1);
        return new ShardState(id, Fixtures.CFG, ShardConfig.defaults(), Fixtures.instruments(), books);
    }

    @Test
    void aBusyShardCannotCrowdOutAnotherShardsSnapshots(@TempDir Path dir) throws Exception {
        ShardState quiet = shard(3), busy = shard(0);
        try (SnapshotStore store = new SnapshotStore(dir, 3)) {
            store.saveAsync(quiet.capture());                      // the quiet shard's one and only snapshot
            for (int i = 0; i < 200; i++) store.saveAsync(busy.capture()); // then a flood from another shard
        }
        try (SnapshotStore store = new SnapshotStore(dir, 3)) {
            assertEquals(1, store.list(3).size(), "the quiet shard's snapshot was written");
            assertFalse(store.list(0).isEmpty());
        }
    }

    @Test
    void writesAreAtomicAndOnlyTheNewestFewAreKept(@TempDir Path dir) throws Exception {
        ShardState s = shard(1);
        try (SnapshotStore store = new SnapshotStore(dir, 3)) {
            for (long offset = 10; offset <= 60; offset += 10) store.save(1, offset, s.snapshot());
            assertEquals(List.of(60L, 50L, 40L), store.list(1).stream().map(SnapshotStore.Saved::offset).toList());
        }
        Files.writeString(dir.resolve("shard-1-00000000000000000070.snap.99.tmp"), "half written");
        new SnapshotStore(dir, 3).close();
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")), "a crash's leftover temporary file is cleaned up");
        }
    }
}
