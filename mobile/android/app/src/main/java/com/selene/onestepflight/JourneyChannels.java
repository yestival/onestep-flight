package com.selene.onestepflight;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/** Quiet journey cards remain eligible for the normal lock-screen card area. */
final class JourneyChannels {
    static final String LEGACY_CARD = "flight_journey";
    static final String CARD = "flight_journey_v2";

    static boolean canMigrateDefault(int oldImportance, boolean userSelected, int api, int visibility) {
        // Channel lock-screen visibility is controlled by the system/user and
        // cannot be copied by an ordinary app. Retain such channels verbatim.
        return api >= 29 && !userSelected && oldImportance == NotificationManager.IMPORTANCE_LOW && visibility == -1000;
    }

    static String configure(NotificationManager manager) {
        return configure(manager, LEGACY_CARD, CARD);
    }

    static String configure(NotificationManager manager, String legacyId, String cardId) {
        if (Build.VERSION.SDK_INT < 26) return cardId;
        if (manager.getNotificationChannel(cardId) != null) return cardId;
        NotificationChannel old = manager.getNotificationChannel(legacyId);
        if (old != null && (!canMigrateDefault(old.getImportance(), Build.VERSION.SDK_INT >= 29 && old.hasUserSetImportance(), Build.VERSION.SDK_INT, old.getLockscreenVisibility())
            || old.getSound() != null || old.shouldVibrate() || old.canBypassDnd() || old.getGroup() != null || !old.canShowBadge())) return legacyId;
        var card = new NotificationChannel(cardId, "OneStep Flight · Journey", NotificationManager.IMPORTANCE_DEFAULT);
        card.setDescription("Current journey · silent updates");
        card.setSound(null, null); card.enableVibration(false);
        manager.createNotificationChannel(card);
        // Retire only the untouched app default after the new quiet channel
        // exists. A channel carrying user choices is kept instead.
        if (old != null) manager.deleteNotificationChannel(legacyId);
        return cardId;
    }
}
