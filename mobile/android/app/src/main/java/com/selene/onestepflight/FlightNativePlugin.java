package com.selene.onestepflight;
import android.content.Intent;
import android.net.Uri;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name="FlightNative")
public class FlightNativePlugin extends Plugin {
    private FlightSpeech speech;
    private FlightSpeech speech() { if (speech == null) speech = new FlightSpeech(getContext()); return speech; }
    @PluginMethod public void speak(PluginCall call) {
        getActivity().runOnUiThread(() -> speech().speak(call.getString("text", ""), call.getString("language", "en-US"), new FlightSpeech.Completion() {
            @Override public void complete(boolean cancelled) { call.resolve(new JSObject().put("cancelled", cancelled)); }
            @Override public void failed(String code) { call.reject("Unable to read aloud", code); }
        }));
    }
    @PluginMethod public void stopSpeech(PluginCall call) {
        getActivity().runOnUiThread(() -> { if (speech != null) speech.stop(); call.resolve(); });
    }
    @Override protected void handleOnPause() { getActivity().runOnUiThread(() -> { if (speech != null) speech.stop(); }); }
    @Override protected void handleOnDestroy() { getActivity().runOnUiThread(() -> { if (speech != null) speech.shutdown(); speech = null; }); }
    @PluginMethod public void dismissKeyboard(PluginCall call) {
      getActivity().runOnUiThread(()->{
        var view=getActivity().getWindow().getDecorView();
        var insets=androidx.core.view.ViewCompat.getRootWindowInsets(view);
        boolean visible=insets!=null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
        if(visible) new androidx.core.view.WindowInsetsControllerCompat(getActivity().getWindow(),view).hide(androidx.core.view.WindowInsetsCompat.Type.ime());
        call.resolve(new JSObject().put("dismissed",visible));
      });
    }
    @PluginMethod public void openExternal(PluginCall call) {
        String raw=call.getString("url",""); Uri uri=Uri.parse(raw);
        boolean https="https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null;
        boolean phone=raw.matches("tel:\\+?[0-9 ()-]{5,25}");
        if(!https && !phone) {call.reject("Unsupported URL");return;}
        try { getActivity().startActivity(new Intent(phone ? Intent.ACTION_DIAL : Intent.ACTION_VIEW,uri));call.resolve(); }
        catch(android.content.ActivityNotFoundException ex) {call.reject("No application can open this link");}
    }
}
