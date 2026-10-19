package com.nikonpreset.usbprobe.account;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM with a non-exportable AndroidKeyStore key. Files live outside Android backup. */
public final class AndroidAccountStore implements AccountRepository.Store {
    private static final String ALIAS = "com.nikonpreset.usbprobe.account.session.v1";
    private static final byte[] AAD = "nikon-account-v1".getBytes(StandardCharsets.US_ASCII);
    private final File directory;
    private final File file;
    private final AtomicFile atomic;

    public AndroidAccountStore(Context context) {
        directory = new File(context.getApplicationContext().getNoBackupFilesDir(), "account");
        file = new File(directory, "session.enc"); atomic = new AtomicFile(file);
    }

    @Override public synchronized byte[] read() throws IOException {
        // AtomicFile also recovers an interrupted write. A missing base with a backup must be read.
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return null;
        byte[] envelope = null;
        try {
            try (FileInputStream input = atomic.openRead()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (n == 0 || n > 96 * 1024 - output.size()) throw new IOException("Invalid encrypted session");
                    output.write(buffer, 0, n);
                }
                envelope = output.toByteArray();
            }
            if (envelope.length < 30 || envelope[0] != 1 || envelope[1] != 12) throw new IOException("Invalid encrypted session");
            SecretKey key = existingKey();
            if (key == null) throw new IOException("Session key unavailable");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, envelope, 2, 12)); cipher.updateAAD(AAD);
            byte[] plain = cipher.doFinal(envelope, 14, envelope.length - 14);
            if (plain.length > AccountRepository.MAX_RESPONSE_BYTES) { Arrays.fill(plain, (byte) 0); throw new IOException("Session too large"); }
            return plain;
        } catch (GeneralSecurityException | RuntimeException | IOException e) {
            try { clear(); } catch (IOException ignored) { }
            throw new AccountException("SESSION_STORAGE_RESET", 0, "本机登录信息已失效，请重新登录。");
        } finally { if (envelope != null) Arrays.fill(envelope, (byte) 0); }
    }

    @Override public synchronized void write(byte[] value) throws IOException {
        if (value == null || value.length > AccountRepository.MAX_RESPONSE_BYTES) throw new IOException("Invalid session size");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private account directory");
        byte[] encrypted = null; FileOutputStream output = null;
        try {
            SecretKey key = existingKey();
            if (key == null) key = createKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(AAD);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != 12) throw new IOException("Unexpected GCM nonce");
            encrypted = cipher.doFinal(value);
            output = atomic.startWrite(); output.write(1); output.write(iv.length); output.write(iv); output.write(encrypted);
            atomic.finishWrite(output); output = null;
        } catch (GeneralSecurityException | RuntimeException e) { throw new IOException("Cannot securely save account session"); }
        finally {
            if (output != null) atomic.failWrite(output);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    @Override public synchronized void clear() throws IOException {
        boolean keyRemoved = false;
        try {
            KeyStore keys = keyStore();
            keys.deleteEntry(ALIAS); keyRemoved = true;
        } catch (GeneralSecurityException | RuntimeException | IOException ignored) { }
        try { atomic.delete(); }
        catch (RuntimeException e) { if (!keyRemoved) throw new IOException("Cannot clear local account session"); }
        boolean filesRemoved = !file.exists() && !new File(file.getPath() + ".bak").exists() && !new File(file.getPath() + ".new").exists();
        // Either destroying the key or deleting every encrypted copy makes old credentials unusable.
        if (!keyRemoved && !filesRemoved) throw new IOException("Cannot clear local account session");
    }

    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null); return keys;
    }
    private static SecretKey existingKey() throws GeneralSecurityException, IOException {
        KeyStore keys = keyStore();
        if (!keys.containsAlias(ALIAS)) return null;
        java.security.Key key = keys.getKey(ALIAS, null);
        if (!(key instanceof SecretKey)) throw new GeneralSecurityException("Invalid account key");
        return (SecretKey) key;
    }
    private static SecretKey createKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
}
