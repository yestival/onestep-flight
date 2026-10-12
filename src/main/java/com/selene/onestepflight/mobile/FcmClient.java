package com.selene.onestepflight.mobile;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Credentials never leave the server. All Android messages are data-only. */
@Component
public class FcmClient {
    private final String credentialsPath, projectId;
    private final ObjectMapper mapper;
    private FirebaseMessaging messaging;
    private volatile boolean authenticationFailed;
    public FcmClient(@Value("${onestep.fcm.credentials-path:}") String credentialsPath,
                     @Value("${onestep.fcm.project-id:}") String projectId, ObjectMapper mapper) {
        this.credentialsPath=credentialsPath; this.projectId=projectId; this.mapper=mapper;
    }
    public boolean ready() {
        try { return !authenticationFailed && !projectId.isBlank() && !credentialsPath.isBlank() && Files.isReadable(Path.of(credentialsPath)); }
        catch(InvalidPathException | SecurityException ex) { return false; }
    }
    private synchronized FirebaseMessaging messaging() throws Exception {
        if(messaging==null) {
            try(var stream=Files.newInputStream(Path.of(credentialsPath))) {
                var options=FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(stream))
                        .setProjectId(projectId).setConnectTimeout(10000).setReadTimeout(20000).build();
                messaging=FirebaseMessaging.getInstance(FirebaseApp.initializeApp(options,"onestep-fcm-"+UUID.randomUUID()));
            }
        }
        return messaging;
    }
    public ApnsClient.Result send(PushDelivery delivery) {
        if(!ready()) return new ApnsClient.Result(0,"FCM_NOT_READY",900);
        var data=new LinkedHashMap<String,String>();
        try {
            var payload=mapper.readTree(delivery.payload);
            if(!payload.isObject()) return new ApnsClient.Result(400,"FCM_INVALID_PAYLOAD",0);
            for(var entry:payload.properties()) {
                if(!entry.getValue().isTextual()) return new ApnsClient.Result(400,"FCM_INVALID_PAYLOAD",0);
                data.put(entry.getKey(),entry.getValue().asText());
            }
            if(dataBytes(data)>3800) return new ApnsClient.Result(413,"PAYLOAD_TOO_LARGE",0);
        } catch(Exception ex) { return new ApnsClient.Result(400,"FCM_INVALID_PAYLOAD",0); }
        FirebaseMessaging client;
        try { client=messaging(); }
        catch(Exception ex) { authenticationFailed=true; return new ApnsClient.Result(403,"FCM_CONFIGURATION_ERROR",0); }
        try {
            var android=AndroidConfig.builder().setPriority(delivery.priority==10 ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL)
                    .setTtl(Math.max(0,delivery.expiresAt-Instant.now().getEpochSecond())*1000);
            if("FCM_SYNC".equals(delivery.channel)) android.setCollapseKey("journey-state");
            client.send(Message.builder().setToken(delivery.token).putAllData(data).setAndroidConfig(android.build()).build());
            return new ApnsClient.Result(200,"ACCEPTED",0);
        } catch(FirebaseMessagingException ex) {
            var code=ex.getMessagingErrorCode();
            if(code==MessagingErrorCode.UNREGISTERED) return new ApnsClient.Result(410,"FCM_UNREGISTERED",0);
            if(code==MessagingErrorCode.SENDER_ID_MISMATCH || code==MessagingErrorCode.THIRD_PARTY_AUTH_ERROR || ex.getErrorCode()==com.google.firebase.ErrorCode.UNAUTHENTICATED || ex.getErrorCode()==com.google.firebase.ErrorCode.PERMISSION_DENIED) {
                authenticationFailed=true; return new ApnsClient.Result(403,"FCM_CREDENTIALS_ERROR",0);
            }
            if(code==MessagingErrorCode.QUOTA_EXCEEDED) return new ApnsClient.Result(429,"FCM_QUOTA_EXCEEDED",60);
            if(code==MessagingErrorCode.INVALID_ARGUMENT) return new ApnsClient.Result(400,"FCM_INVALID_ARGUMENT",0);
            return new ApnsClient.Result(503,"FCM_TEMPORARY_ERROR",60);
        } catch(Exception ex) { return new ApnsClient.Result(400,"FCM_INVALID_PAYLOAD",0); }
    }
    static int dataBytes(Map<?,?> data) {
        return data.entrySet().stream().mapToInt(e->(e.getKey().toString()+e.getValue().toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
    }
}
