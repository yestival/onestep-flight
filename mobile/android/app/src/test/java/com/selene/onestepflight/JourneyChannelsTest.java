package com.selene.onestepflight;

import android.app.NotificationManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class JourneyChannelsTest {
    @Test public void appDefaultLowCanBecomeAQuietNormalCard() {
        assertTrue(JourneyChannels.canMigrateDefault(NotificationManager.IMPORTANCE_LOW, false, 34, -1000));
    }
    @Test public void userImportanceAndDisabledChannelsAreRetained() {
        for (int importance : new int[] {0, 1, 2, 3, 4})
            assertFalse(JourneyChannels.canMigrateDefault(importance, true, 34, -1000));
        assertFalse(JourneyChannels.canMigrateDefault(0, false, 34, -1000));
    }
    @Test public void olderAndroidDoesNotGuessWhetherLowWasUserSelected() {
        assertFalse(JourneyChannels.canMigrateDefault(NotificationManager.IMPORTANCE_LOW, false, 28, -1000));
    }
    @Test public void explicitLockscreenPrivacyKeepsTheExistingChannel() {
        for(int visibility:new int[]{-1,0,1}) assertFalse(JourneyChannels.canMigrateDefault(2,false,34,visibility));
    }
}
