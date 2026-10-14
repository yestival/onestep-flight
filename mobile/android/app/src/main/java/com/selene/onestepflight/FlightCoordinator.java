package com.selene.onestepflight;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.*;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.ConnectionResult;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

final class FlightCoordinator {
    static com.getcapacitor.JSObject js(JSONObject object) {
        var result=new com.getcapacitor.JSObject();object.keys().forEachRemaining(k->result.put(k,object.opt(k)));return result;
    }
    static final String CARD=JourneyChannels.CARD, ALERT="flight_changes";
    static final int CARD_ID=117;
    private static FlightCoordinator instance;
    final Context context;
    final JourneyStore store;
    final String cardChannel;
    private String error="";
    private boolean initialized;
    private boolean tokenLoading;
    private String pendingRoute;
    java.util.function.Consumer<String> routeListener;
    java.util.function.Consumer<JSONObject> stateListener;
    static synchronized FlightCoordinator get(Context context) {
        if(instance==null) instance=new FlightCoordinator(context.getApplicationContext());
        return instance;
    }
    private FlightCoordinator(Context context) {
        this.context=context; store=new JourneyStore(context);
        cardChannel=Build.VERSION.SDK_INT>=26 ? JourneyChannels.configure(context.getSystemService(NotificationManager.class)) : CARD;
        if(Build.VERSION.SDK_INT>=26) {
            var manager=context.getSystemService(NotificationManager.class);
            var alerts=new NotificationChannel(ALERT,"OneStep Flight · Important changes",NotificationManager.IMPORTANCE_HIGH);
            alerts.setDescription("Gate, terminal and important flight changes");
            manager.createNotificationChannel(alerts);
        }
    }
    synchronized void initialize() {
        if(initialized) { refreshToken(); return; }
        initialized=true;
        try { store.identity(); } catch(Exception ex) { error="KEYSTORE_UNAVAILABLE"; return; }
        reconcileDismissal(); refreshToken(); schedule(); sweepShares();
        var cleanup=new PeriodicWorkRequest.Builder(FlightCleanupWorker.class,24,TimeUnit.HOURS).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("flight-cache-cleanup",ExistingPeriodicWorkPolicy.KEEP,cleanup);
    }
    boolean configured() {
        try { return !FirebaseApp.getApps(context).isEmpty() || FirebaseApp.initializeApp(context)!=null; }
        catch(Exception ex) { return false; }
    }
    boolean googleAvailable() { return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)==ConnectionResult.SUCCESS; }
    synchronized void refreshToken() {
        if(tokenLoading || !googleAvailable() || !configured()) return;
        tokenLoading=true;
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task->{
            synchronized(this) {
                tokenLoading=false;
                if(task.isSuccessful() && task.getResult()!=null) setToken(task.getResult());
                else { store.prefs.edit().putString("tokenError","FCM_REGISTRATION_FAILED").commit(); schedule(); }
            }
        });
    }
    synchronized void setToken(String token) {
        store.prefs.edit().putString("token",token).remove("tokenError").commit(); schedule();
    }
    boolean notificationsEnabled() {
        return (Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            && NotificationManagerCompat.from(context).areNotificationsEnabled();
    }
    boolean channelEnabled(String channel) {
        if(Build.VERSION.SDK_INT<26) return true;
        var c=context.getSystemService(NotificationManager.class).getNotificationChannel(channel);
        return c!=null && c.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    synchronized JSONObject liveStatus() {
        reconcileDismissal();
        var out=new JSONObject(); boolean enabled=notificationsEnabled() && channelEnabled(cardChannel);
        boolean active=false;
        for(var n:context.getSystemService(NotificationManager.class).getActiveNotifications()) if(n.getId()==CARD_ID) active=true;
        JourneyMessage.put(out,"supported",true); JourneyMessage.put(out,"enabled",enabled);
        JourneyMessage.put(out,"localPersistenceSupported",true);
        JourneyMessage.put(out,"active",active); JourneyMessage.put(out,"journeyKey",store.key());
        JourneyMessage.put(out,"dismissedJourneyKey",store.prefs.getString("dismissedJourneyKey",""));
        JourneyMessage.put(out,"removed",store.key().equals(store.prefs.getString("dismissedJourneyKey","")) && !store.key().isEmpty());
        JourneyMessage.put(out,"errorCode",error); JourneyMessage.put(out,"lockScreenVisibility","system-controlled");
        JourneyMessage.put(out,"notificationPermission",notificationsEnabled()); JourneyMessage.put(out,"alertChannelEnabled",channelEnabled(ALERT));
        JourneyMessage.put(out,"pushStatus",!googleAvailable() ? "GOOGLE_SERVICES_UNAVAILABLE" : !configured() ? "FIREBASE_NOT_CONFIGURED" : store.prefs.getString("serverPushStatus","FCM_SERVER_UNCONFIRMED"));
        return out;
    }
    synchronized JSONObject pushStatus() {
        var out=liveStatus(); String reason=!googleAvailable() ? "GOOGLE_SERVICES_UNAVAILABLE" : !configured() ? "FIREBASE_NOT_CONFIGURED"
            : !notificationsEnabled() ? "NOTIFICATIONS_DISABLED" : !channelEnabled(cardChannel) || !channelEnabled(ALERT) ? "NOTIFICATION_CHANNEL_DISABLED"
            : store.prefs.getString("tokenError","").isEmpty() ? store.prefs.getString("token","").isEmpty() ? "FCM_REGISTERING" : store.prefs.getString("serverPushStatus","FCM_SERVER_UNCONFIRMED") : "FCM_REGISTRATION_FAILED";
        JourneyMessage.put(out,"ready",error.isEmpty()); JourneyMessage.put(out,"pushStatus",reason);
        JourneyMessage.put(out,"pushReady","READY".equals(reason)); JourneyMessage.put(out,"platform","android");
        JourneyMessage.put(out,"clientRevision",store.revision()); JourneyMessage.put(out,"snapshotVersion",store.prefs.getLong("snapshotVersion",0));
        JourneyMessage.put(out,"pendingOperations",store.array("operations").length());
        JSONObject display=store.object("display"); if(display.has("journeyKey")) JourneyMessage.put(out,"displayState",display);
        return out;
    }
    synchronized JSONObject sync(JSONObject input) {
        initialize(); String key=input.optString("journeyKey");
        if(key.isEmpty() || input.optJSONObject("flight")==null) return pushStatus();
        var flight=input.optJSONObject("flight");
        if(flight.optBoolean("demo") || flight.has("demoSessionId") && !flight.isNull("demoSessionId") || flight.has("demoScenario") && !flight.isNull("demoScenario")) {
            if(!store.key().isEmpty() && !store.key().equals(key)) clear(store.key());
            store.prefs.edit().putBoolean("demo",true).commit();return pushStatus();
        }
        select(key);
        String fingerprint=key+input.optJSONObject("progress")+input.optString("locale");
        if(!fingerprint.equals(store.prefs.getString("fingerprint",""))) { store.nextRevision(); store.prefs.edit().putString("fingerprint",fingerprint).commit(); }
        JourneyMessage.put(input,"clientRevision",store.revision());
        store.prefs.edit().putString("language",input.optString("locale","en")).putBoolean("demo",false).commit();
        enqueue("PUT","/journey",input); schedule(); return pushStatus();
    }
    private void select(String key) {
        if(key.equals(store.key()) && !store.prefs.getBoolean("ended",false)) return;
        if(!store.key().isEmpty()) clear(store.key());
        store.prefs.edit().putString("journeyKey",key).putBoolean("ended",false).remove("display").remove("fingerprint")
            .remove("dismissedJourneyKey").putBoolean("posted",false).putLong("snapshotVersion",0).commit();
        long generation=store.nextRevision();store.prefs.edit().putLong("journeyGeneration",generation).commit();
    }
    synchronized JSONObject local(JSONObject display) {
        initialize(); String key=display.optString("journeyKey"); if(key.isEmpty()) {error="INVALID_DISPLAY_STATE";return liveStatus();}
        if(key.equals(store.key()) && store.prefs.getBoolean("ended",false)) return liveStatus();
        select(key);
        store.prefs.edit().putBoolean("demo",display.optBoolean("demo",false)).commit();
        if(display.optLong("journeyEndsAt")<=System.currentTimeMillis()/1000) { clear(key); return liveStatus(); }
        var previous=store.object("display");
        // The server owns newer operational facts; the shared display contract owns guidance.
        long version=store.prefs.getLong("snapshotVersion",0);
        if(display.optLong("snapshotVersion")<version) display=previous;
        else display=JourneyMessage.retain(previous,display);
        JourneyMessage.put(display,"clientRevision",store.revision()); JourneyMessage.put(display,"snapshotVersion",version);
        store.save("display",display); postCard(display); scheduleFreshness(display); return liveStatus();
    }
    synchronized void clear(String expectedKey) {
        if(expectedKey==null || expectedKey.isEmpty() || !expectedKey.equals(store.key())) return;
        String old=store.key(); long revision=store.nextRevision();
        store.prefs.edit().putBoolean("ended",true).putString("journeyKey","").remove("display").remove("fingerprint").putBoolean("posted",false).commit();
        var q=store.array("operations"); var kept=new JSONArray();
        for(int n=0;n<q.length();n++) { var o=q.optJSONObject(n); if(o!=null && !"PUT".equals(o.optString("method"))) kept.put(o); }
        store.save("operations",kept);
        enqueue("DELETE","/journey?clientRevision="+revision+"&journeyKey="+android.net.Uri.encode(old),null);
        context.getSystemService(NotificationManager.class).cancel(CARD_ID);
        cancelAlerts(old); WorkManager.getInstance(context).cancelUniqueWork("flight-freshness"); schedule();
    }
    synchronized void enqueue(String method,String path,JSONObject body) {
        var q=store.array("operations"); var kept=new JSONArray();
        for(int n=0;n<q.length();n++) { var o=q.optJSONObject(n); if(o!=null && !("PUT".equals(method) && path.equals(o.optString("path")) && !o.optString("id").equals(store.prefs.getString("inflight","")))) kept.put(o); }
        var op=new JSONObject(); JourneyMessage.put(op,"id",UUID.randomUUID().toString()); JourneyMessage.put(op,"method",method); JourneyMessage.put(op,"path",path); if(body!=null) JourneyMessage.put(op,"body",body); kept.put(op); store.save("operations",kept);
    }
    synchronized void schedule() {
        var request=new OneTimeWorkRequest.Builder(FlightSyncWorker.class).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork("flight-sync",ExistingWorkPolicy.APPEND_OR_REPLACE,request);
    }
    synchronized void receive(Map<String,String> data) {
        try {
            JSONObject envelope=new JSONObject(data); String event=data.getOrDefault("eventId","");
            var seen=store.array("seenEvents");
            for(int n=0;n<seen.length();n++) if(event.equals(seen.optString(n))) return;
            if(event.isEmpty() || !JourneyMessage.accepts(store.key(),store.revision(),store.prefs.getLong("snapshotVersion",0),store.prefs.getBoolean("ended",false),envelope,System.currentTimeMillis()/1000)) return;
            JSONObject state=new JSONObject(data.getOrDefault("displayState","{}"));
            if(!store.key().equals(state.optString("journeyKey")) || state.optLong("snapshotVersion")!=envelope.optLong("snapshotVersion") || state.optLong("clientRevision")!=envelope.optLong("clientRevision")) return;
            var retained=JourneyMessage.retain(store.object("display"),state);
            JourneyMessage.put(retained,"facts",JourneyMessage.retain(store.object("display").optJSONObject("facts"),state.optJSONObject("facts")==null ? new JSONObject() : state.optJSONObject("facts")));
            // Older servers can omit raw facts; these identity-free fields map directly.
            for(String field:List.of("gate","terminal","arrivalTerminal","baggageBelt")) {
                String value=state.optString(field,"");
                if(!value.isEmpty() && !value.equals(state.optString("missingLabel")) && !List.of("待确认","To be confirmed","Por confirmar").contains(value)) JourneyMessage.put(retained.getJSONObject("facts"),"baggageBelt".equals(field) ? "arrivalBaggageBelt" : field,value);
            }
            store.save("display",retained);
            store.prefs.edit().putLong("snapshotVersion",state.optLong("snapshotVersion")).commit();
            seen.put(event); var limited=new JSONArray(); for(int n=Math.max(0,seen.length()-128);n<seen.length();n++) limited.put(seen.optString(n)); store.save("seenEvents",limited);
            if(Boolean.parseBoolean(data.getOrDefault("important","false"))) postAlert(data,retained);
            boolean end=Boolean.parseBoolean(data.getOrDefault("end","false"));
            if(end) { store.prefs.edit().putBoolean("ended",true).putBoolean("posted",false).commit(); context.getSystemService(NotificationManager.class).cancel(CARD_ID); }
            else { reconcileDismissal(); postCard(retained); scheduleFreshness(retained); }
            if(stateListener!=null) stateListener.accept(pushStatus());
        } catch(Exception ex) { error="INVALID_PUSH_STATE"; }
    }
    synchronized void serverState(JSONObject response) {
        store.prefs.edit().putString("serverPushStatus",response.optString("pushStatus","FCM_SERVER_UNCONFIRMED")).commit();
        var state=response.optJSONObject("displayState"); if(state==null || !JourneyMessage.accepts(store.key(),store.revision(),store.prefs.getLong("snapshotVersion",0),store.prefs.getBoolean("ended",false),state,System.currentTimeMillis()/1000)) return;
        store.save("display",JourneyMessage.retain(store.object("display"),state)); store.prefs.edit().putLong("snapshotVersion",state.optLong("snapshotVersion")).commit();
        if(!response.optBoolean("active",true)) { store.prefs.edit().putBoolean("ended",true).putBoolean("posted",false).commit(); context.getSystemService(NotificationManager.class).cancel(CARD_ID); }
        else { reconcileDismissal(); postCard(store.object("display")); scheduleFreshness(state); }
        if(stateListener!=null) stateListener.accept(pushStatus());
    }
    private PendingIntent click(String key,int request) {
        Intent intent=new Intent(context,MainActivity.class).setAction("onestepflight.JOURNEY").setData(android.net.Uri.parse("onestepflight://journey?journeyKey="+android.net.Uri.encode(key)))
            .putExtra("journeyKey",key).putExtra("journeyGeneration",store.prefs.getLong("journeyGeneration",0)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context,request,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    synchronized void route(Intent intent) {
        if(intent==null) return; String key=intent.getStringExtra("journeyKey");
        if(key==null || !key.equals(store.key()) || store.prefs.getBoolean("ended",false) || intent.getLongExtra("journeyGeneration",-1)!=store.prefs.getLong("journeyGeneration",0)) return;
        pendingRoute=key; if(routeListener!=null) { routeListener.accept(key); pendingRoute=null; }
    }
    synchronized void deliverRoute() { if(pendingRoute!=null && routeListener!=null) {routeListener.accept(pendingRoute);pendingRoute=null;} }
    private void reconcileDismissal() {
        if(!store.prefs.getBoolean("posted",false)) return;
        if(!notificationsEnabled() || !channelEnabled(cardChannel)) { store.prefs.edit().putBoolean("posted",false).commit(); return; }
        // NotificationManager accepts a post before System UI lists it. Absence
        // during that short handoff is not a user dismissal.
        if(System.currentTimeMillis()-store.prefs.getLong("postedAt",0)<3000) return;
        boolean found=false; for(var notification:context.getSystemService(NotificationManager.class).getActiveNotifications()) if(notification.getId()==CARD_ID) found=true;
        // Android can clear cards during permission changes, package replacement
        // or system recovery. Missing from the shade is not proof of a swipe.
        // Only the scoped deleteIntent records an explicit user dismissal.
        if(!found) store.prefs.edit().putBoolean("posted",false).commit();
    }
    synchronized void dismissed(String key) {
        if(key==null || key.isEmpty() || !key.equals(store.key())) return;
        store.prefs.edit().putString("dismissedJourneyKey",key).putBoolean("posted",false).commit();
        context.getSystemService(NotificationManager.class).cancel(CARD_ID);
    }
    synchronized void dismissed(String key,long generation) {
        if(generation==store.prefs.getLong("journeyGeneration",0) && notificationsEnabled() && channelEnabled(cardChannel)) dismissed(key);
    }
    private NotificationCompat.Builder base(String channel,String key,int request) {
        return new NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_flight_notification).setColor(0xff2c648e)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(click(key,request))
            .setPublicVersion(new NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_flight_notification).setContentTitle("OneStep Flight").setContentText("Journey update").build());
    }
    private void postCard(JSONObject d) {
        if(store.prefs.getBoolean("ended",false) || !notificationsEnabled() || !channelEnabled(cardChannel) || store.key().equals(store.prefs.getString("dismissedJourneyKey",""))) return;
        try {
            String flight=d.optString("flightNumber")+" · "+d.optString("flightDate")+" · "+d.optString("departureAirport")+" → "+d.optString("arrivalAirport");
            String detail=d.optString("metricOneLabel")+": "+d.optString("metricOneValue")+"\n"+d.optString("metricTwoLabel")+": "+d.optString("metricTwoValue")+"\n"+d.optString("metricThreeLabel")+": "+d.optString("metricThreeValue");
            String footer=d.optString("updatedText")+(d.optBoolean("offline") ? " · "+d.optString("offlineLabel") : "");
            long generation=store.prefs.getLong("journeyGeneration",0);
            Intent dismissed=new Intent(context,JourneyDismissReceiver.class).setData(android.net.Uri.parse("onestepflight://dismiss/"+android.net.Uri.encode(store.key())+"?generation="+generation))
                .putExtra("journeyKey",store.key()).putExtra("journeyGeneration",generation);
            var notification=base(cardChannel,store.key(),CARD_ID).setContentTitle(d.optString("nextStep")).setContentText(flight)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(flight+"\n"+detail+"\n"+footer)).setSubText(d.optString("updatedText"))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setDeleteIntent(PendingIntent.getBroadcast(context,0,dismissed,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build();
            context.getSystemService(NotificationManager.class).notify(CARD_ID,notification); store.prefs.edit().putBoolean("posted",true).putLong("postedAt",System.currentTimeMillis()).commit(); error="";
        } catch(SecurityException ex) { error="NOTIFICATIONS_DISABLED"; }
    }
    private void postAlert(Map<String,String> data,JSONObject state) {
        if(!notificationsEnabled() || !channelEnabled(ALERT) || store.prefs.getBoolean("demo",false)) return;
        String event=data.get("eventId"); int id=1000+(event.hashCode() & 0x3fffffff);
        var notification=base(ALERT,store.key(),id).setContentTitle(data.getOrDefault("title",state.optString("flightNumber")))
            .setContentText(data.getOrDefault("body",state.optString("nextStep"))).setStyle(new NotificationCompat.BigTextStyle().bigText(data.getOrDefault("body",state.optString("nextStep"))))
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_EVENT).setPriority(NotificationCompat.PRIORITY_HIGH).setTimeoutAfter(900000).build();
        try { context.getSystemService(NotificationManager.class).notify(id,notification); } catch(SecurityException ignored) { return; }
        var ids=store.array("alerts"); ids.put(id); store.save("alerts",ids);
    }
    private void cancelAlerts(String key) { var ids=store.array("alerts"); for(int n=0;n<ids.length();n++) context.getSystemService(NotificationManager.class).cancel(ids.optInt(n)); store.save("alerts",new JSONArray()); }
    private void scheduleFreshness(JSONObject d) {
        long now=System.currentTimeMillis()/1000;
        long deadline=d.optLong("staleAt")>now ? d.optLong("staleAt") : d.optLong("journeyEndsAt");
        if(deadline<=now) return;
        var work=new OneTimeWorkRequest.Builder(FlightFreshnessWorker.class).setInitialDelay(deadline-now,TimeUnit.SECONDS).setInputData(new Data.Builder().putString("journeyKey",store.key()).build()).build();
        WorkManager.getInstance(context).enqueueUniqueWork("flight-freshness",ExistingWorkPolicy.REPLACE,work);
    }
    synchronized void refreshCard(String expected) {
        if(expected==null || !expected.equals(store.key()) || store.prefs.getBoolean("ended",false)) return;
        var display=store.object("display");
        if(display.optLong("journeyEndsAt")<=System.currentTimeMillis()/1000) clear(expected);
        else {reconcileDismissal(); postCard(display); scheduleFreshness(display);}
    }
    void sweepShares() {
        var directory=new java.io.File(context.getCacheDir(),"onestep-share"); var files=directory.listFiles();
        if(files!=null) for(var file:files) if(file.isFile() && file.lastModified()<System.currentTimeMillis()-86400000L) { if(!file.delete()) file.deleteOnExit(); }
    }
}
