package dev.opencal.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM encrypted SharedPreferences backed by an Android Keystore key. */
final class Secrets {
    private static final String ALIAS = "open_cal_key_v1";
    private final SharedPreferences prefs;

    Secrets(Context context) {
        prefs = context.getSharedPreferences("open_cal_v1", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }

    void put(String name, String value) throws Exception {
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(name).apply();
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String saved = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                       Base64.encodeToString(encrypted, Base64.NO_WRAP);
        prefs.edit().putString(name, saved).apply();
    }

    String get(String name) {
        try {
            String data = prefs.getString(name, null);
            if (data == null) return "";
            String[] pieces = data.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return ""; // Keystore keys may be invalidated when the device is reset.
        }
    }
}
