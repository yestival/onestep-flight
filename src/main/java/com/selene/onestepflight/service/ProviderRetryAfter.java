package com.selene.onestepflight.service;

import java.time.Instant;
import org.springframework.web.client.RestClientResponseException;
import com.selene.onestepflight.mobile.ApnsClient;

/** Carry Retry-After through the existing provider result without storing response bodies. */
public final class ProviderRetryAfter {
    private ProviderRetryAfter() {}
    public static String code(String code,RestClientResponseException response,Instant now) {
        String raw=response.getResponseHeaders()==null ? "" : response.getResponseHeaders().getFirst("Retry-After");
        long seconds=ApnsClient.retryAfter(raw==null ? "" : raw,now.getEpochSecond());
        return seconds>0 ? code+";retryAt="+(now.getEpochSecond()+seconds) : code;
    }
    public static long seconds(String code,long now) {
        if(code==null || !code.contains(";retryAt=")) return 0;
        try { return Math.max(0,Long.parseLong(code.substring(code.indexOf(";retryAt=")+9))-now); } catch(Exception ignored) { return 0; }
    }
}
