package com.selene.onestepflight;
import android.content.*;
import java.util.*;
import org.json.JSONObject;

/** Debug source set only: exercises precisely the FCM service's processing chain. */
public class DebugMessageReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(!BuildConfig.DEBUG) return;
        try {
            if(intent.getBooleanExtra("localTest",false)) {
                FlightCoordinator.get(context).store.prefs.edit().putBoolean("localInjectionTest",true).commit();
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("flight-sync");setResultCode(1);return;
            }
            String data=intent.getStringExtra("data");
            if(data==null) try(var stream=new java.io.FileInputStream(new java.io.File(context.getFilesDir(),"debug-message.json"))) {data=new String(FlightSyncWorker.readBounded(stream,8192),java.nio.charset.StandardCharsets.UTF_8);}
            var object=new JSONObject(data); Map<String,String> values=new HashMap<>(); object.keys().forEachRemaining(k->values.put(k,object.optString(k)));
            FlightCoordinator.get(context).receive(values);setResultCode(1);
        } catch(Exception ex) {setResultCode(0);}
    }
}
