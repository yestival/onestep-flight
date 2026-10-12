package com.selene.onestepflight;
import android.content.Intent;
import android.net.Uri;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name="FlightNative")
public class FlightNativePlugin extends Plugin {
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
