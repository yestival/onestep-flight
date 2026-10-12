package com.selene.onestepflight;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.ValueCallback;
import com.getcapacitor.CapacitorWebView;

/** Keep framework lifecycle dispatch inside the lifetime of its JS bridge. */
public class FlightWebView extends CapacitorWebView {
    public FlightWebView(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
        // Capacitor's Cordova compatibility layer posts pause/resume to the UI
        // queue. Activity recreation can deliver these to the old blank document
        // or before the new document has received its bridge. There are no JS
        // listeners in either document; the ready app obtains its current state
        // through App.getState and then subscribes to native lifecycle changes.
        if (script != null && script.startsWith("window.Capacitor.triggerEvent(")) {
            script = "if (window.Capacitor && typeof window.Capacitor.triggerEvent === 'function') {" + script + "}";
        }
        super.evaluateJavascript(script, callback);
    }
}
