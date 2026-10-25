package io.github.xmy953538104.mybox.engine;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import static org.junit.Assert.*;

public final class ExitHistoryJournalTest {
    private static final String BOOT = "11111111-2222-3333-4444-555555555555";
    private static ExitHistoryJournal.Clock at(long wall) { return new ExitHistoryJournal.Clock(BOOT, wall, wall - 9000); }
    private static ExitHistoryJournal.Row row(String key, int pid, long wall) { return new ExitHistoryJournal.Row(key, "host:engine_p0", pid, wall); }
    private static void seal(ExitHistoryJournal journal, String pkg, int pid, long generation, long wall) throws IOException {
        journal.seal(pkg, pkg.equals("a") ? 10001 : 10002, 42, pkg + ":worker", "host:engine_p0", pid, generation, at(wall), Collections.emptyList());
    }

    @Test public void actualRowsAreOwnedAcrossColdRecoveryAndSlotReuse() throws Exception {
        byte[][] disk = {null}; ExitHistoryJournal journal = new ExitHistoryJournal(null, bytes -> disk[0] = bytes);
        seal(journal, "a", 10, 1, 10000); journal.retired(10, 1, at(11000));
        seal(journal, "b", 11, 2, 12000); journal.retired(11, 2, at(13000));
        ExitHistoryJournal.Row own = row("own", 10, 11050), peer = row("peer", 11, 13050), host = row("host", 12, 13060);
        Map<String, ExitHistoryJournal.Owner> result = journal.resolve("a", 10001, 42, Arrays.asList(peer, own, host), at(14000));
        assertEquals(Collections.singleton("own"), result.keySet()); assertEquals("a:worker", result.get("own").process);
        String epoch = journal.epoch("a", 10001, 42);
        ExitHistoryJournal restored = new ExitHistoryJournal(disk[0], bytes -> disk[0] = bytes);
        assertEquals(epoch, restored.epoch("a", 10001, 42));
        assertEquals(Collections.singleton("own"), restored.resolve("a", 10001, 42, Arrays.asList(peer, own), at(15000)).keySet());
        // A genuine update retains the trusted install identity; a replacement installation does not.
        seal(restored, "a", 13, 1, 16000);
        assertEquals(epoch, restored.epoch("a", 10001, 42));
        restored.seal("a", 10001, 43, "a", "host:engine_p1", 14, 2, at(17000), Collections.emptyList());
        assertNotEquals(epoch, restored.epoch("a", 10001, 43));
        assertTrue(restored.resolve("a", 10001, 43, Collections.singletonList(own), at(18000)).isEmpty());
    }

    @Test public void reusedPidBaselineAndClockDiscontinuityNeverBorrowOwnership() throws Exception {
        ExitHistoryJournal journal = new ExitHistoryJournal(null, bytes -> { });
        seal(journal, "a", 10, 1, 10000); journal.retired(10, 1, at(11000));
        assertEquals(Collections.singleton("known"), journal.resolve("a", 10001, 42, Collections.singletonList(row("known", 10, 11000)), at(11500)).keySet());
        journal.seal("b", 10002, 42, "b", "host:engine_p0", 10, 2, at(12000), Collections.singletonList("old"));
        assertTrue(journal.resolve("b", 10002, 42, Arrays.asList(row("old", 10, 12500), row("a", 10, 11000)), at(13000)).isEmpty());
        // The old PID record can gain a new timestamp/key after the replacement reservation. Never assign it to B.
        assertTrue(journal.resolve("b", 10002, 42, Collections.singletonList(row("changed-old", 10, 12500)), at(14000)).isEmpty());
        assertEquals(Collections.singleton("known"), journal.resolve("a", 10001, 42, Collections.singletonList(row("known", 10, 11000)), at(14500)).keySet());
        assertTrue(journal.resolve("a", 10001, 42, Collections.singletonList(row("other", 10, 12500)), at(15000)).isEmpty());
        // Epoch time rolled back while elapsed advanced: do not newly correlate any pending record.
        assertTrue(journal.resolve("a", 10001, 42, Collections.singletonList(row("late", 10, 11050)),
                new ExitHistoryJournal.Clock(BOOT, 12000, 7000)).isEmpty());
        assertEquals(1, journal.discontinuities());
        ExitHistoryJournal other = new ExitHistoryJournal(null, bytes -> { }); seal(other, "a", 10, 1, 10000);
        assertTrue(other.resolve("a", 10001, 42, Collections.singletonList(row("newboot", 10, 11000)),
                new ExitHistoryJournal.Clock("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 12000, 100)).isEmpty());
    }

    @Test public void corruptionAndWriteFailureRemainErrorsAndDoNotPublishOwnership() throws Exception {
        byte[][] disk = {null}; boolean[] fail = {false};
        ExitHistoryJournal journal = new ExitHistoryJournal(null, bytes -> { if (fail[0]) throw new IOException("disk"); disk[0] = bytes; });
        seal(journal, "a", 10, 1, 10000); byte[] original = disk[0].clone();
        fail[0] = true;
        try { journal.resolve("a", 10001, 42, Collections.singletonList(row("own", 10, 11000)), at(12000)); fail(); }
        catch (IOException expected) { assertEquals("disk", expected.getMessage()); }
        assertArrayEquals(original, disk[0]);
        byte[] damaged = original.clone(); damaged[10] ^= 1;
        try { new ExitHistoryJournal(damaged, bytes -> { }); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("checksum")); }
        try { new ExitHistoryJournal(Arrays.copyOf(original, 12), bytes -> { }); fail(); }
        catch (IOException expected) { }
    }

    @Test public void retiredInstallMappingsAreReclaimedWithoutALifetimeInstallLimit() throws Exception {
        ExitHistoryJournal journal = new ExitHistoryJournal(null, bytes -> { });
        journal.seal("old", 10000, 42, "old", "host:engine_p0", 100, 1, at(10000), Collections.emptyList());
        journal.retired(100, 1, at(10001));
        assertEquals(Collections.singleton("kept"), journal.resolve("old", 10000, 42, Collections.singletonList(row("kept", 100, 10001)), at(10002)).keySet());
        String oldEpoch = journal.epoch("old", 10000, 42);
        for (int i = 1; i <= 350; i++) {
            long time = 10000 + i * 10L;
            journal.seal("app" + i, 10000 + i, 42, "app" + i, "host:engine_p0", 100 + i, i + 1, at(time), Collections.emptyList());
            journal.retired(100 + i, i + 1, at(time + 1));
        }
        assertEquals(oldEpoch, journal.epoch("old", 10000, 42));
        assertEquals(Collections.singleton("kept"), journal.resolve("old", 10000, 42, Collections.singletonList(row("kept", 100, 10001)), at(14000)).keySet());
        assertEquals(Collections.singleton("new"), journal.resolve("app350", 10350, 42, Collections.singletonList(row("new", 450, 13501)), at(14001)).keySet());
    }

    @Test public void clockAmbiguityDoesNotMakeALiveReservationEvictable() throws Exception {
        ExitHistoryJournal journal = new ExitHistoryJournal(null, bytes -> { });
        for (int i = 0; i < 256; i++) {
            journal.seal("a", 10001, 42, "a:p" + i, "host:engine_p0", 100 + i, i + 1,
                    at(10000 + i), Collections.emptyList());
        }
        String epoch = journal.epoch("a", 10001, 42);
        journal.resolve("a", 10001, 42, Collections.emptyList(), new ExitHistoryJournal.Clock(BOOT, 11000, 5000));
        assertEquals(1, journal.discontinuities());
        try {
            journal.seal("a", 10001, 42, "a:new", "host:engine_p0", 500, 257,
                    new ExitHistoryJournal.Clock(BOOT, 11001, 5001), Collections.emptyList());
            fail("A live but ambiguous process was evicted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("process quota")); }
        assertEquals(epoch, journal.epoch("a", 10001, 42));
    }
}
