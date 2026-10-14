package com.selene.onestepflight;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.util.UUID;

/** Uses isolated channel IDs, without changing the user's journey or channels. */
public class JourneyChannelsMigrationTest {
    @Test public void legacyAppDefaultMigratesQuietlyAndInheritsSystemPrivacy() {
        assumeTrue(Build.VERSION.SDK_INT >= 26);
        var manager=ApplicationProvider.<Context>getApplicationContext().getSystemService(NotificationManager.class);
        String legacy="test-old-"+UUID.randomUUID(), current="test-card-"+UUID.randomUUID();
        try {
            var old=new NotificationChannel(legacy,"Test legacy",NotificationManager.IMPORTANCE_LOW);
            old.setSound(null,null);old.enableVibration(false);
            manager.createNotificationChannel(old);
            var previous=manager.getNotificationChannel(legacy);
            String selected=JourneyChannels.configure(manager,legacy,current);
            assertEquals(Build.VERSION.SDK_INT>=29 ? current : legacy,selected);
            var migrated=manager.getNotificationChannel(selected);
            assertEquals(Build.VERSION.SDK_INT>=29 ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_LOW,migrated.getImportance());
            assertEquals(previous.getLockscreenVisibility(),migrated.getLockscreenVisibility());
            assertNull(migrated.getSound());assertFalse(migrated.shouldVibrate());
            if(Build.VERSION.SDK_INT>=29) assertNull(manager.getNotificationChannel(legacy));
        } finally { manager.deleteNotificationChannel(legacy);manager.deleteNotificationChannel(current); }
    }
    @Test public void disabledLegacyChannelStaysDisabledAfterMigration() {
        assumeTrue(Build.VERSION.SDK_INT >= 26);
        var manager=ApplicationProvider.<Context>getApplicationContext().getSystemService(NotificationManager.class);
        String legacy="test-old-"+UUID.randomUUID(), current="test-card-"+UUID.randomUUID();
        try {
            manager.createNotificationChannel(new NotificationChannel(legacy,"Test disabled",NotificationManager.IMPORTANCE_NONE));
            assertEquals(legacy,JourneyChannels.configure(manager,legacy,current));
            assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel(legacy).getImportance());
            assertNull(manager.getNotificationChannel(current));
        } finally { manager.deleteNotificationChannel(legacy);manager.deleteNotificationChannel(current); }
    }
    @Test public void legacyCustomSoundKeepsTheOriginalChannelAndItsSettings() {
        assumeTrue(Build.VERSION.SDK_INT >= 26);
        var manager=ApplicationProvider.<Context>getApplicationContext().getSystemService(NotificationManager.class);
        String legacy="test-old-"+UUID.randomUUID(), current="test-card-"+UUID.randomUUID();
        try {
            var old=new NotificationChannel(legacy,"Custom sound",NotificationManager.IMPORTANCE_LOW);
            old.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,null);
            manager.createNotificationChannel(old);
            assertEquals(legacy,JourneyChannels.configure(manager,legacy,current));
            assertEquals(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,manager.getNotificationChannel(legacy).getSound());
            assertNull(manager.getNotificationChannel(current));
        } finally { manager.deleteNotificationChannel(legacy);manager.deleteNotificationChannel(current); }
    }
    @Test public void repeatedConfigurationKeepsTheCurrentUserChoice() {
        assumeTrue(Build.VERSION.SDK_INT >= 26);
        var manager=ApplicationProvider.<Context>getApplicationContext().getSystemService(NotificationManager.class);
        String legacy="test-old-"+UUID.randomUUID(), current="test-card-"+UUID.randomUUID();
        try {
            manager.createNotificationChannel(new NotificationChannel(current,"User disabled",NotificationManager.IMPORTANCE_NONE));
            manager.createNotificationChannel(new NotificationChannel(legacy,"Test legacy",NotificationManager.IMPORTANCE_LOW));
            JourneyChannels.configure(manager,legacy,current);JourneyChannels.configure(manager,legacy,current);
            assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel(current).getImportance());
        } finally { manager.deleteNotificationChannel(legacy);manager.deleteNotificationChannel(current); }
    }
}
