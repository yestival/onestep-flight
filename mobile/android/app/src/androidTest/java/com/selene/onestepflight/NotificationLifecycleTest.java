package com.selene.onestepflight;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;

/** Runs on an Android emulator; injected data uses the identical FCM handler. */
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class NotificationLifecycleTest {
    private FlightCoordinator c;
    private NotificationManager notifications;
    private JSONObject display(String key) throws Exception {
        return new JSONObject().put("journeyKey",key).put("flightNumber","BA 117").put("flightDate","2026-10-11").put("departureAirport","LHR").put("arrivalAirport","JFK")
            .put("nextStep","Go to gate B32").put("headingIncludesGate",true).put("gate","B32").put("terminal","T5").put("boardingTime","14:30")
            .put("metricOneLabel","Terminal").put("metricOneValue","T5").put("metricTwoLabel","Boarding").put("metricTwoValue","14:30")
            .put("metricThreeLabel","Departure").put("metricThreeValue","15:20").put("timezoneText","Departure airport local time").put("updatedText","14:00 updated")
            .put("staleLabel","Data may be out of date").put("offlineLabel","Refresh when online").put("staleAt",System.currentTimeMillis()/1000+600)
            .put("journeyEndsAt",System.currentTimeMillis()/1000+36000).put("snapshotVersion",0).put("clientRevision",0);
    }
    @Before public void prepare() throws Exception {
        Context context=ApplicationProvider.getApplicationContext(); c=FlightCoordinator.get(context);
        c.store.prefs.edit().clear().putBoolean("localInjectionTest",true).commit(); notifications=context.getSystemService(NotificationManager.class);notifications.cancelAll();
        if(Build.VERSION.SDK_INT>=33) InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        assertCount(0);
    }
    private Map<String,String> event(String id,String key,long version,long revision,boolean important,boolean end) throws Exception {
        var d=display(key).put("gate","B40").put("nextStep","Go to gate B40").put("snapshotVersion",version).put("clientRevision",revision);
        return Map.of("eventId",id,"journeyKey",key,"snapshotVersion",Long.toString(version),"clientRevision",Long.toString(revision),"important",Boolean.toString(important),"end",Boolean.toString(end),"expiresAt",Long.toString(System.currentTimeMillis()/1000+900),"displayState",d.toString(),"title","Gate changed","body","B32 → B40");
    }
    private int count() {return notifications.getActiveNotifications().length;}
    private void assertCount(int expected) throws Exception {
        // NotificationManager posts and cancels asynchronously. Require the
        // observed state to settle, including assertions that no card returns.
        int stable=0;
        for(int n=0;n<30;n++) {
            stable=count()==expected ? stable+1 : 0;
            if(stable>=3) return;
            Thread.sleep(100);
        }
        fail("Notification count did not settle to "+expected+"; last count was "+count());
    }
    @Test public void localCardWorksWithoutFirebaseAndKeepsGateOutOfMetricArea() throws Exception {
        c.local(display("one").put("stale",true).put("staleAt",System.currentTimeMillis()/1000-60));assertCount(1);assertTrue(c.liveStatus().getBoolean("active"));
        var card=notifications.getActiveNotifications()[0].getNotification();
        assertEquals("Go to gate B32",card.extras.getString("android.title"));
        assertFalse(card.extras.getCharSequence("android.bigText").toString().contains("T5 · B32"));
        String expanded=card.extras.getCharSequence("android.bigText").toString();
        assertTrue(expanded.contains("14:00 updated"));
        assertFalse(expanded.contains("Departure airport local time"));
        assertFalse(expanded.contains("Data may be out of date"));
        assertEquals("14:00 updated",card.extras.getString("android.subText"));
        assertTrue(c.pushStatus().getString("pushStatus").contains("FIREBASE_NOT_CONFIGURED"));
        assertEquals(2,c.store.identity().length()); assertFalse(c.store.prefs.getString("identity","").contains("secret"));
    }
    @Test public void duplicateAndOutOfOrderEventsDoNotMakeDuplicateAlerts() throws Exception {
        c.local(display("one"));long revision=c.store.revision();var message=event("change","one",4,revision,true,false);
        c.receive(message);assertCount(2);c.receive(message);assertCount(2);
        c.receive(event("old","one",3,revision,true,false));assertCount(2);
        c.receive(event("old-progress","one",5,revision-1,true,false));assertEquals(4,c.store.prefs.getLong("snapshotVersion",0));
        assertEquals("B40",c.store.object("display").getString("gate"));
        c.store.nextRevision();c.local(display("one"));
        assertEquals("B40",c.store.object("display").getString("gate"));
        assertEquals("B40",c.store.object("display").getJSONObject("facts").getString("gate"));
    }
    @Test public void oldPushAndOldClearCannotAffectReplacementAndEndIsIdempotent() throws Exception {
        c.local(display("old"));long revision=c.store.revision();c.local(display("new"));
        c.receive(event("late","old",99,revision,true,false));c.clear("old");assertEquals("new",c.store.key());assertCount(1);
        var end=event("end","new",2,c.store.revision(),false,true);c.receive(end);c.receive(end);
        assertTrue(c.store.prefs.getBoolean("ended",false));assertCount(0);
        c.receive(event("late-new","new",3,c.store.revision(),true,false));assertCount(0);
        c.local(display("new"));assertCount(0);
    }
    @Test public void dismissalSuppressesCardButAllowsImportantAlerts() throws Exception {
        c.local(display("one"));assertCount(1);c.dismissed("one");notifications.cancel(FlightCoordinator.CARD_ID);
        c.local(display("one"));assertCount(0);
        c.receive(event("change","one",2,c.store.revision(),true,false));assertCount(1);
        var alert=notifications.getActiveNotifications()[0];assertNotEquals(FlightCoordinator.CARD_ID,alert.getId());
        if(Build.VERSION.SDK_INT>=26) assertEquals(FlightCoordinator.ALERT,alert.getNotification().getChannelId());
        assertTrue(c.liveStatus().getBoolean("removed"));assertFalse(c.liveStatus().getBoolean("active"));
    }
    @Test public void systemCancellationDoesNotBecomeAUserDismissal() throws Exception {
        c.local(display("one"));assertCount(1);
        notifications.cancel(FlightCoordinator.CARD_ID);assertCount(0);
        c.store.prefs.edit().putLong("postedAt",0).commit();
        assertFalse(c.liveStatus().getBoolean("removed"));
        c.refreshCard("one");assertCount(1);
        c.dismissed("one",c.store.prefs.getLong("journeyGeneration",0));assertCount(0);
        c.refreshCard("one");assertCount(0);
        assertTrue(c.liveStatus().getBoolean("removed"));
    }
    @Test public void zDisabledChannelPreservesLocalJourneyWithoutRecreatingTheChannel() throws Exception {
        if(Build.VERSION.SDK_INT<26) return;
        notifications.deleteNotificationChannel(FlightCoordinator.CARD);
        notifications.createNotificationChannel(new android.app.NotificationChannel(FlightCoordinator.CARD,"Journey",NotificationManager.IMPORTANCE_NONE));
        c.local(display("one"));assertEquals("one",c.store.key());assertFalse(c.liveStatus().getBoolean("enabled"));assertCount(0);
        c.refreshCard("one");assertCount(0);
        assertEquals("B32",c.store.object("display").getString("gate"));
        assertEquals(NotificationManager.IMPORTANCE_NONE,notifications.getNotificationChannel(FlightCoordinator.CARD).getImportance());
    }
    @Test public void demoReplacementRevokesRealJourneyAndCannotReceiveItsPush() throws Exception {
        c.local(display("real"));long revision=c.store.revision();
        c.sync(new JSONObject().put("journeyKey","demo:real").put("flight",new JSONObject().put("demo",true)));
        c.local(display("demo:real").put("demo",true));
        c.receive(event("real-change","real",99,revision,true,false));assertCount(1);
        assertEquals("B32",c.store.object("display").getString("gate"));
        var operations=c.store.array("operations");
        for(int n=0;n<operations.length();n++) assertNotEquals("PUT",operations.getJSONObject(n).getString("method"));
    }
    @Test public void oldDismissalAndClickCannotAffectRestartOfTheSameFlight() throws Exception {
        c.local(display("one"));long generation=c.store.prefs.getLong("journeyGeneration",0);
        c.clear("one");c.local(display("one"));assertCount(1);
        c.dismissed("one",generation);assertFalse(c.liveStatus().getBoolean("removed"));
        var routed=new ArrayList<String>();c.routeListener=routed::add;
        c.route(new android.content.Intent().putExtra("journeyKey","one").putExtra("journeyGeneration",generation));
        assertTrue(routed.isEmpty());
        c.route(new android.content.Intent().putExtra("journeyKey","one").putExtra("journeyGeneration",c.store.prefs.getLong("journeyGeneration",0)));
        assertEquals(List.of("one"),routed);c.routeListener=null;
    }
    @Test public void fileProviderCanReadShareAfterChooserAndCleanupKeepsRecentFiles() throws Exception {
        var context=ApplicationProvider.<Context>getApplicationContext();
        var directory=new java.io.File(context.getCacheDir(),"onestep-share");assertTrue(directory.isDirectory() || directory.mkdirs());
        var recent=new java.io.File(directory,"card-recent.png");var old=new java.io.File(directory,"card-old.png");
        byte[] bytes={1,2,3};
        try(var stream=new java.io.FileOutputStream(recent)) {stream.write(bytes);}
        try(var stream=new java.io.FileOutputStream(old)) {stream.write(bytes);}
        assertTrue(old.setLastModified(System.currentTimeMillis()-90000000));
        var uri=androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".fileprovider",recent);
        assertEquals("content",uri.getScheme());
        c.sweepShares();assertFalse(old.exists());assertTrue(recent.exists());
        try(var stream=context.getContentResolver().openInputStream(uri)) {assertArrayEquals(bytes,FlightSyncWorker.readBounded(stream,16));}
        assertTrue(recent.delete());
    }
}
