package com.selene.onestepflight.mobile;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApnsClient {
    public record Result(int status,String reason,long retryAfter) {}
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private final String teamId,keyId,bundleId,keyPath;
    private final ObjectMapper mapper;
    private String cachedJwt;
    private long jwtAt;
    private boolean authenticationFailed;
    public ApnsClient(@Value("${onestep.apns.team-id:}") String teamId,@Value("${onestep.apns.key-id:}") String keyId,
            @Value("${onestep.apns.bundle-id:com.selene.onestepflight}") String bundleId,@Value("${onestep.apns.private-key-path:}") String keyPath,ObjectMapper mapper) {
        this.teamId=teamId; this.keyId=keyId; this.bundleId=bundleId; this.keyPath=keyPath; this.mapper=mapper;
    }
    public boolean ready() { return !authenticationFailed && teamId.matches("[A-Z0-9]{10}") && keyId.matches("[A-Z0-9]{10}") && !keyPath.isBlank() && Files.isReadable(Path.of(keyPath)); }
    private static String base64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static byte[] joseSignature(PrivateKey key,byte[] content) throws GeneralSecurityException {
        Signature signature=Signature.getInstance("SHA256withECDSAinP1363Format"); signature.initSign(key); signature.update(content);
        byte[] result=signature.sign(); if(result.length!=64) throw new SignatureException("ES256 requires 64-byte JOSE R||S"); return result;
    }
    private synchronized String jwt(long now) throws Exception {
        if(cachedJwt!=null && now-jwtAt<50*60) return cachedJwt;
        String pem=Files.readString(Path.of(keyPath),StandardCharsets.US_ASCII).replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
        PrivateKey key=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        String input=base64(mapper.writeValueAsBytes(Map.of("alg","ES256","kid",keyId)))+"."+base64(mapper.writeValueAsBytes(Map.of("iss",teamId,"iat",now)));
        cachedJwt=input+"."+base64(joseSignature(key,input.getBytes(StandardCharsets.US_ASCII))); jwtAt=now; return cachedJwt;
    }
    public Result send(PushDelivery delivery) {
        if(!ready()) return new Result(0,"APNS_NOT_READY",900);
        byte[] payload=delivery.payload.getBytes(StandardCharsets.UTF_8);
        if(payload.length>4096) return new Result(413,"PAYLOAD_TOO_LARGE",0);
        if(!Set.of("sandbox","production").contains(delivery.environment)) return new Result(400,"INVALID_ENVIRONMENT",0);
        boolean activity="ACTIVITY".equals(delivery.channel);
        String host="sandbox".equals(delivery.environment) ? "api.sandbox.push.apple.com" : "api.push.apple.com";
        try {
            long now=Instant.now().getEpochSecond();
            var request=HttpRequest.newBuilder(URI.create("https://"+host+"/3/device/"+delivery.token)).timeout(Duration.ofSeconds(20))
                    .header("authorization","bearer "+jwt(now)).header("apns-id",delivery.id)
                    .header("apns-topic",bundleId+(activity ? ".push-type.liveactivity" : ""))
                    .header("apns-push-type",activity ? "liveactivity" : "alert").header("apns-priority",Integer.toString(delivery.priority))
                    .header("apns-expiration",Long.toString(delivery.expiresAt)).POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String reason=response.statusCode()==200 ? "ACCEPTED" : mapper.readTree(response.body()).path("reason").asText("APNS_REJECTED");
            if(response.statusCode()==403) authenticationFailed=true;
            return new Result(response.statusCode(),reason,retryAfter(response.headers().firstValue("retry-after").orElse(""),now));
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); return new Result(503,"INTERRUPTED",60); }
        catch(GeneralSecurityException ex) { authenticationFailed=true; return new Result(403,"INVALID_SIGNING_KEY",0); }
        catch(Exception ex) { return new Result(503,"APNS_TRANSPORT_ERROR",60); }
    }
    public static long retryAfter(String value,long now) {
        try { return Math.max(0,Long.parseLong(value)); } catch(Exception ignored) {}
        try { return Math.max(0,ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()-now); } catch(Exception ignored) { return 0; }
    }
}
