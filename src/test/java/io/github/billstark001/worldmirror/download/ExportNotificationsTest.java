package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExportNotificationsTest {
    @Test
    void notificationHealthReadsAreDrivenByFailuresAndDeadlines() {
        var notices = new ExportNotifications();
        var unhealthy = health("a",true);
        var healthy = new ExportStatus.Health("a",ExportStatus.Counts.EMPTY,false,ExportStatus.FailureStage.NONE);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<String,ExportStatus.Health> current = source -> { reads.incrementAndGet(); return unhealthy; };
        for (int source = 0; source < 100; source++) notices.observe(new ExportStatus.Health("healthy-"+source,healthy.counts(),false,healthy.stage()));
        for (int tick = 0; tick < 600; tick++) assertNull(notices.next(tick*50,current));
        assertEquals(0,reads.get(),"healthy sources must not create a polling history");
        notices.observe(unhealthy);
        assertNotNull(notices.next(30_000,current));
        for (int tick = 1; tick < 600; tick++) assertNull(notices.next(30_000+tick*50,current));
        assertEquals(1,reads.get(),"no per-tick source scan between reminders");
        assertNotNull(notices.next(60_000,current));
        notices.observe(healthy);
        assertTrue(notices.next(62_000,source -> healthy).recovered());
        for (int tick = 0; tick < 600; tick++) assertNull(notices.next(62_000+tick*50,current));
        assertEquals(2,reads.get(),"recovered sources leave the notification queue");
    }
    @Test
    void queuedWorkCancelsUndeliveredRecoveryAndNewFailuresTakePriorityOverReminders() {
        var notices = new ExportNotifications();
        notices.observe(health("a",true));
        notices.poll(0);
        notices.observe(new ExportStatus.Health("a",ExportStatus.Counts.EMPTY,false,ExportStatus.FailureStage.NONE));
        var queued = new ExportStatus.Health("a",new ExportStatus.Counts(1,0,0,0,0,0,0,0),false,ExportStatus.FailureStage.NONE);
        notices.refresh(source -> queued);
        assertNull(notices.poll(2_000), "new accepted work prevents a stale recovery notice");
        notices.observe(health("a",true));
        notices.observe(health("new",true));
        assertEquals("new",notices.poll(30_000).health().source(), "reminders cannot starve first failures");
        assertEquals("a",notices.poll(32_000).health().source());
    }
    @Test
    void automaticFailuresAreDeliveredAndRepeatedFailuresCoalesce() {
        var notices = new ExportNotifications();
        var store = new ExportStatusStore();
        var automatic = ExportStatusStoreTest.work("automatic", 1, 2);
        assertFalse(automatic.context().notifyPlayer());
        store.queued(automatic);
        notices.observe(store.finish(automatic, ExportStatusStoreTest.outcome(automatic, true, false, true)));
        assertFalse(notices.poll(0).recovered());
        for (int i = 1; i < 100; i++) notices.observe(store.health("automatic"));
        assertNull(notices.poll(29_999));
        assertEquals(99, notices.poll(30_000).merged());
        assertNull(notices.poll(30_001));
    }

    @Test
    void escalationBypassesSourceReminderButHonorsGlobalDeliverySpacing() {
        var notices = new ExportNotifications();
        notices.observe(health("a", false));
        assertFalse(notices.poll(0).health().blocked());
        notices.observe(health("a", true));
        assertNull(notices.poll(1_999));
        assertTrue(notices.poll(2_000).health().blocked());
        assertNull(notices.poll(2_001));
    }

    @Test
    void laterTerrainSuccessDoesNotSendRecoveryForFailedEntities() {
        var notices = new ExportNotifications();
        var store = new ExportStatusStore();
        var old = ExportStatusStoreTest.work("a", 1, 2);
        store.queued(old);
        notices.observe(store.finish(old, ExportStatusStoreTest.outcome(old, true, false, true)));
        notices.poll(0);
        var next = ExportStatusStoreTest.work("a", 2, -1);
        store.queued(next);
        notices.observe(store.finish(next, ExportStatusStoreTest.outcome(next, true, true, true)));
        assertNull(notices.poll(2_000));
        notices.observe(store.finish(old, ExportStatusStoreTest.outcome(old, true, true, true)));
        assertTrue(notices.poll(3_000).recovered());
        assertNull(notices.poll(60_000));
    }

    @Test
    void missingPlayerDeliveryKeepsLatestNoticeAndSourcesAreIndependent() {
        var notices = new ExportNotifications();
        for (int i = 0; i < 100; i++) notices.observe(health("old", true));
        notices.observe(health("new", false));
        assertEquals(100, notices.poll(1_000).merged());
        assertNull(notices.poll(2_000));
        assertEquals("new", notices.poll(3_000).health().source());
    }

    private static ExportStatus.Health health(String source, boolean blocked) {
        return new ExportStatus.Health(source, new ExportStatus.Counts(0,0,blocked ? 1 : 0,blocked ? 0 : 1,0,1,blocked ? 1 : 0,0),
                false, blocked ? ExportStatus.FailureStage.TERRAIN : ExportStatus.FailureStage.ENTITIES);
    }
}
