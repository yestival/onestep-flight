package com.selene.onestepflight;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.RequiresDevice;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Refreshes the real saved journey, without clearing data or inventing a flight. */
@RequiresDevice
public class CurrentJourneyCardDeviceTest {
    @Test public void savedJourneyPostsAQuietReadableCardWhileTheWebViewIsStopped() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        var coordinator=FlightCoordinator.get(context);
        String key=coordinator.store.key();
        assumeTrue("A previously confirmed journey is required",!key.isEmpty() && !coordinator.store.prefs.getBoolean("ended",false));
        assertTrue("Notifications are disabled by the user",coordinator.notificationsEnabled());
        assertFalse("User dismissed this card",coordinator.liveStatus().getBoolean("removed"));
        var state=coordinator.store.object("display");
        var manager=context.getSystemService(NotificationManager.class);
        coordinator.refreshCard(key);
        Notification posted=null;
        for(int attempt=0;attempt<40 && posted==null;attempt++) {
            for(var item:manager.getActiveNotifications()) if(item.getId()==FlightCoordinator.CARD_ID) posted=item.getNotification();
            if(posted==null) Thread.sleep(100);
        }
        assertNotNull("Saved journey was not posted",posted);
        assertEquals(state.getString("nextStep"),posted.extras.getString("android.title"));
        assertTrue(posted.extras.getString("android.text").contains(state.getString("flightNumber")));
        assertTrue(posted.extras.getString("android.text").contains(state.getString("departureAirport")));
        assertTrue(posted.extras.getString("android.text").contains(state.getString("arrivalAirport")));
        assertEquals(Notification.VISIBILITY_PRIVATE,posted.visibility);
        if(Build.VERSION.SDK_INT>=26) {
            var channel=manager.getNotificationChannel(FlightCoordinator.CARD);
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT,channel.getImportance());
            assertNull(channel.getSound());assertFalse(channel.shouldVibrate());
        }
        assertEquals(key,coordinator.store.key());
    }
}
