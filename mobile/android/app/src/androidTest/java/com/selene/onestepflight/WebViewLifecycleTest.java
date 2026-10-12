package com.selene.onestepflight;

import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebViewLifecycleTest {
    @Test public void lifecycleBeforeBridgeInjectionDoesNotThrow() throws Exception { dispatch(false); }
    @Test public void lifecycleAfterBridgeInjectionStillReachesDocument() throws Exception { dispatch(true); }

    private void dispatch(boolean ready) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var finished = new CountDownLatch(1);
        var result = new AtomicReference<String>();
        var errors = new ArrayList<String>();
        var view = new AtomicReference<FlightWebView>();
        instrumentation.runOnMainSync(() -> {
            var webView = new FlightWebView(instrumentation.getTargetContext(), null);
            view.set(webView);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onConsoleMessage(ConsoleMessage message) {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) errors.add(message.message());
                    return true;
                }
            });
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView web, String url) {
                    webView.evaluateJavascript("window.Capacitor.triggerEvent('resume', 'document');", ignored ->
                        webView.evaluateJavascript("window.delivered", value -> {result.set(value);finished.countDown();}));
                }
            });
            String bridge = ready ? "window.Capacitor={triggerEvent:function(name,target){if(name==='resume'&&target==='document')window.delivered++;}};" : "";
            webView.loadDataWithBaseURL("https://localhost/", "<html><script>window.delivered=0;"+bridge+"</script></html>", "text/html", "UTF-8", null);
        });
        try {
            assertTrue("Lifecycle dispatch callback must finish", finished.await(15, TimeUnit.SECONDS));
            assertEquals(ready ? "1" : "0", result.get());
            assertEquals("Lifecycle dispatch must not produce a JavaScript error", List.of(), errors);
        } finally { instrumentation.runOnMainSync(() -> view.get().destroy()); }
    }
}
