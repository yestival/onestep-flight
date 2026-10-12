package com.selene.onestepflight;

import org.json.JSONObject;

/** Ordering shared by FCM, recovery responses and the debug injection path. */
final class JourneyMessage {
    static boolean accepts(String currentKey, long revision, long version, boolean ended, JSONObject incoming, long now) {
        return !ended && !currentKey.isEmpty() && currentKey.equals(incoming.optString("journeyKey"))
            && incoming.optLong("clientRevision", -1) >= revision
            && incoming.optLong("snapshotVersion", -1) >= version
            && incoming.optLong("expiresAt", Long.MAX_VALUE) > now;
    }
    static JSONObject retain(JSONObject old, JSONObject fresh) {
        JSONObject result = new JSONObject();
        if (old != null) old.keys().forEachRemaining(k -> put(result,k,old.opt(k)));
        fresh.keys().forEachRemaining(k -> {
            Object value = fresh.opt(k);
            boolean missing = value == null || value == JSONObject.NULL || "".equals(value)
                || value.equals(fresh.optString("missingLabel")) || java.util.Set.of("-","—","–","N/A","NA","TBA","TBD","UNKNOWN","UNAVAILABLE","NOT_FOUND","NULL","TO BE CONFIRMED","TO BE ANNOUNCED","待确认","待公布","POR CONFIRMAR","POR ANUNCIAR").contains(value.toString().trim().toUpperCase(java.util.Locale.ROOT));
            if(value instanceof JSONObject object && result.optJSONObject(k)!=null) put(result,k,retain(result.optJSONObject(k),object));
            else if (!missing || !result.has(k)) put(result,k,value);
        });
        return result;
    }
    static void put(JSONObject object, String key, Object value) { try { object.put(key,value); } catch (Exception ex) { throw new IllegalArgumentException(ex); } }
}
