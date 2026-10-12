package com.selene.onestepflight;

import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class FlightMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(@NonNull String token) { FlightCoordinator.get(this).setToken(token); }
    @Override public void onMessageReceived(@NonNull RemoteMessage message) { FlightCoordinator.get(this).receive(message.getData()); }
    @Override public void onDeletedMessages() { FlightCoordinator.get(this).schedule(); }
}
