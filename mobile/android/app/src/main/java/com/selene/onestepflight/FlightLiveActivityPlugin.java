package com.selene.onestepflight;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;

@CapacitorPlugin(name="FlightLiveActivity")
public class FlightLiveActivityPlugin extends Plugin {
    private FlightCoordinator coordinator() {return FlightCoordinator.get(getContext());}
    @PluginMethod public void status(PluginCall call) {call.resolve(FlightCoordinator.js(coordinator().liveStatus()));}
    @PluginMethod public void start(PluginCall call) {save(call);}
    @PluginMethod public void update(PluginCall call) {
        if(!coordinator().store.key().equals(call.getString("journeyKey",""))) {status(call);return;}
        save(call);
    }
    private void save(PluginCall call) {
        try {call.resolve(FlightCoordinator.js(coordinator().local(new JSONObject(call.getData().toString()))));}
        catch(Exception ex) {call.reject("Unable to save journey display","NATIVE_DISPLAY_FAILED");}
    }
    @PluginMethod public void end(PluginCall call) {coordinator().clear(call.getString("journeyKey",""));status(call);}
}
