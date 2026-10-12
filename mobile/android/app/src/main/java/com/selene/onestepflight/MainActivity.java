package com.selene.onestepflight;

import com.getcapacitor.BridgeActivity;
import android.content.Intent;
import android.os.Bundle;
import androidx.activity.EdgeToEdge;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FlightNativePlugin.class);
        registerPlugin(FlightPushPlugin.class);
        registerPlugin(FlightLiveActivityPlugin.class);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        FlightCoordinator.get(this).route(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);FlightCoordinator.get(this).route(intent);}
    @Override public void onResume() {super.onResume();var c=FlightCoordinator.get(this);c.initialize();c.refreshCard(c.store.key());c.schedule();}
}
