package com.selene.onestepflight;
import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import org.json.JSONObject;

@CapacitorPlugin(name="FlightPush",permissions={@Permission(alias="notifications",strings={Manifest.permission.POST_NOTIFICATIONS})})
public class FlightPushPlugin extends Plugin {
    private java.util.function.Consumer<String> routeListener;
    private java.util.function.Consumer<JSONObject> stateListener;
    private FlightCoordinator coordinator() {return FlightCoordinator.get(getContext());}
    @Override public void load() {
        routeListener=key->notifyListeners("journeyRoute",new JSObject().put("journeyKey",key),true);
        stateListener=state->notifyListeners("journeyStatus",FlightCoordinator.js(state));
        coordinator().routeListener=routeListener;coordinator().stateListener=stateListener;
    }
    @Override protected void handleOnDestroy() {
        if(coordinator().routeListener==routeListener) coordinator().routeListener=null;
        if(coordinator().stateListener==stateListener) coordinator().stateListener=null;
    }
    @PluginMethod public void initialize(PluginCall call) {coordinator().initialize();coordinator().deliverRoute();status(call);}
    @PluginMethod public void status(PluginCall call) {call.resolve(FlightCoordinator.js(coordinator().pushStatus()));}
    @PluginMethod public void syncJourney(PluginCall call) {
        try {
            JSONObject input=new JSONObject(call.getData().toString());
            call.resolve(FlightCoordinator.js(coordinator().sync(input)));
            // Resolve before prompting; a declined permission never blocks the page.
            if(Build.VERSION.SDK_INT>=33 && !coordinator().store.prefs.getBoolean("demo",false) && !coordinator().store.prefs.getBoolean("permissionAsked",false) && !coordinator().notificationsEnabled()) {
                coordinator().store.prefs.edit().putBoolean("permissionAsked",true).commit();
                requestPermissionForAlias("notifications",call,"notificationResult");
            }
        } catch(Exception ex) {call.reject("Unable to save journey","NATIVE_SYNC_FAILED");}
    }
    @PermissionCallback private void notificationResult(PluginCall call) {
        coordinator().refreshCard(coordinator().store.key());coordinator().schedule();
        notifyListeners("journeyStatus",FlightCoordinator.js(coordinator().pushStatus()));
    }
    @PluginMethod public void clearJourney(PluginCall call) {coordinator().clear(call.getString("journeyKey",""));status(call);}
    @PluginMethod public void openSettings(PluginCall call) {
        Intent intent=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getContext().getPackageName());
        getActivity().startActivity(intent);call.resolve();
    }
}
