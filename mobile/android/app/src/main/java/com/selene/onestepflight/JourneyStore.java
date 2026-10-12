package com.selene.onestepflight;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

final class JourneyStore {
    final SharedPreferences prefs;
    private static final String ALIAS = "onestep.installation.v1";
    JourneyStore(Context context) { prefs=context.getSharedPreferences("flight-native",Context.MODE_PRIVATE); }
    JSONObject object(String key) { try { return new JSONObject(prefs.getString(key,"{}")); } catch(Exception ex) { return new JSONObject(); } }
    JSONArray array(String key) { try { return new JSONArray(prefs.getString(key,"[]")); } catch(Exception ex) { return new JSONArray(); } }
    void save(String key,Object value) { prefs.edit().putString(key,value.toString()).commit(); }
    String key() { return prefs.getString("journeyKey",""); }
    long revision() { return prefs.getLong("revision",0); }
    long nextRevision() { long r=revision()+1; prefs.edit().putLong("revision",r).commit(); return r; }
    // No plaintext secret ever enters Preferences, WebView or a public snapshot.
    synchronized JSONObject identity() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        boolean existed=store.containsAlias(ALIAS);
        if(!existed) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generator.generateKey();
        }
        SecretKey key=(SecretKey)store.getKey(ALIAS,null);
        String saved=prefs.getString("identity",null);
        if(saved!=null && existed) {
            try {
                String[] parts=saved.split(":"); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
                return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
            } catch(Exception ex) { throw new java.security.GeneralSecurityException("KEYSTORE_UNAVAILABLE"); }
        }
        // A restored preference without its device-only Keystore key is a new install.
        if(saved!=null) prefs.edit().clear().commit();
        byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
        JSONObject identity=new JSONObject().put("id",UUID.randomUUID().toString()).put("secret",Base64.encodeToString(secret,Base64.NO_WRAP));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key);
        String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        prefs.edit().putString("identity",encrypted).commit(); return identity;
    }
}
