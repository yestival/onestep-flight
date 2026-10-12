package com.selene.onestepflight;
import android.content.*;
public class JourneyDismissReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) { FlightCoordinator.get(context).dismissed(intent.getStringExtra("journeyKey"),intent.getLongExtra("journeyGeneration",-1)); }
}
