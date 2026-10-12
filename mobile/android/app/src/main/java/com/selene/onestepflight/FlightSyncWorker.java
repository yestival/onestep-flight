package com.selene.onestepflight;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Durable bounded compensation; flight polling remains on the Java server. */
public class FlightSyncWorker extends Worker {
    public FlightSyncWorker(Context context,WorkerParameters params) { super(context,params); }
    @NonNull @Override public Result doWork() {
        var c=FlightCoordinator.get(getApplicationContext());
        // Only the debug source set can enable this flag. Local injection tests
        // exercise persisted work without sending their fixtures to production.
        if(BuildConfig.DEBUG && c.store.prefs.getBoolean("localInjectionTest",false)) return Result.success();
        try {
            var identity=c.store.identity();
            var origin=URI.create(BuildConfig.FLIGHT_API_ORIGIN);
            if(!"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) return Result.failure();
            String path="/api/mobile/installations/"+identity.getString("id");
            var register=new JSONObject().put("id",identity.getString("id")).put("platform","android");
            if(!send(c,origin,"/api/mobile/installations","POST",register,identity.getString("secret"))) return Result.retry();
            if(c.googleAvailable() && c.configured() && c.store.prefs.getString("token","").isEmpty()) {
                try { String token=Tasks.await(FirebaseMessaging.getInstance().getToken(),10,TimeUnit.SECONDS); c.store.prefs.edit().putString("token",token).remove("tokenError").commit(); }
                catch(Exception ex) { c.store.prefs.edit().putString("tokenError","FCM_REGISTRATION_FAILED").commit(); }
            }
            var installation=new JSONObject().put("platform","android").put("pushProvider","fcm").put("environment","production")
                .put("deviceToken",c.store.prefs.getString("token","")).put("authorization",c.notificationsEnabled() ? "authorized" : "denied").put("language",c.store.prefs.getString("language","en"));
            if(!send(c,origin,path,"PUT",installation,identity.getString("secret"))) return Result.retry();
            for(int count=0;count<20;count++) {
                JSONObject op;
                synchronized(c) {
                    op=c.store.array("operations").optJSONObject(0); if(op==null) break;
                    var body=op.optJSONObject("body");
                    if("/journey".equals(op.optString("path")) && body!=null && (!c.store.key().equals(body.optString("journeyKey")) || body.optLong("clientRevision")<c.store.revision() || c.store.prefs.getBoolean("ended",false))) { remove(c,op.optString("id")); continue; }
                    c.store.prefs.edit().putString("inflight",op.optString("id")).commit();
                }
                boolean ok=send(c,origin,path+op.getString("path"),op.getString("method"),op.optJSONObject("body"),identity.getString("secret"));
                synchronized(c) {c.store.prefs.edit().remove("inflight").commit(); if(ok) remove(c,op.getString("id"));}
                if(!ok) return Result.retry();
            }
            if(!c.store.key().isEmpty()) send(c,origin,path+"/journey","GET",null,identity.getString("secret"));
            c.sweepShares();
            return c.store.array("operations").length()>0 || c.googleAvailable() && c.configured() && c.store.prefs.getString("token","").isEmpty() ? Result.retry() : Result.success();
        } catch(Exception ex) { return Result.retry(); }
    }
    private static void remove(FlightCoordinator c,String id) {
        var q=c.store.array("operations"); var keep=new JSONArray();
        for(int n=0;n<q.length();n++) { var o=q.optJSONObject(n); if(o!=null && !id.equals(o.optString("id"))) keep.put(o); } c.store.save("operations",keep);
    }
    private boolean send(FlightCoordinator c,URI origin,String path,String method,JSONObject body,String secret) throws Exception {
        var connection=(javax.net.ssl.HttpsURLConnection)origin.resolve(path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method); connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
        connection.setRequestProperty("Authorization","Bearer "+secret); connection.setRequestProperty("Content-Type","application/json");
        try {
            if(body!=null) {connection.setDoOutput(true); try(var stream=connection.getOutputStream()) {stream.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=connection.getResponseCode();
            if(code>=200 && code<300) {
                try(var stream=connection.getInputStream()) { var bytes=readBounded(stream,65536); if(bytes.length>0) c.serverState(new JSONObject(new String(bytes,StandardCharsets.UTF_8))); }
                return true;
            }
            if(code==409) {
                try(var stream=connection.getErrorStream()) { if(stream!=null && new String(readBounded(stream,2048),StandardCharsets.UTF_8).contains("RETRY_CONCURRENT_WRITE")) return false; }
                return true;
            }
            c.store.prefs.edit().putString("syncError","HTTP_"+code).commit();
            // Retain authentication/configuration failures for future recovery.
            return false;
        } finally { connection.disconnect(); }
    }
    static byte[] readBounded(java.io.InputStream stream,int limit) throws java.io.IOException {
        var out=new java.io.ByteArrayOutputStream();var buffer=new byte[4096];int count;
        while(out.size()<limit && (count=stream.read(buffer,0,Math.min(buffer.length,limit-out.size())))!=-1) out.write(buffer,0,count);
        return out.toByteArray();
    }
}
