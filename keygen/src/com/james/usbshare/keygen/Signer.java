package com.james.usbshare.keygen;

import com.james.usbshare.license.License;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Signs license keys with the owner's private key file (never shipped inside the apps). */
public final class Signer {
    public static final String HEADER = "JAMES-USB-SHARE-LICENSE-PRIVATE-KEY-V1";
    private final PrivateKey key;

    public Signer(Path keyFile) throws Exception {
        String[] lines = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim().split("\\r?\\n");
        if (lines.length < 2 || !lines[0].trim().equals(HEADER)) throw new Exception("ไม่ใช่ไฟล์กุญแจของ JAMES USB Share");
        key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines[1].trim())));
    }

    /** @param expiryDay 0 = lifetime, otherwise License.dayOf(last valid day) */
    public String issue(byte[] deviceHash, int expiryDay) throws Exception {
        int today = License.dayOf(System.currentTimeMillis());
        byte[] payload = License.buildPayload(deviceHash, expiryDay, today);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(payload);
        return License.encodeKey(payload, License.derToRaw(s.sign()));
    }

    /** One key for one PC and (optionally) one phone. Either id may be null, not both. */
    public String issue(byte[] pcHash, byte[] phoneHash, int expiryDay) throws Exception {
        int today = License.dayOf(System.currentTimeMillis());
        byte[] payload = License.buildPayload2(pcHash, phoneHash, expiryDay, today);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(payload);
        return License.encodeKey(payload, License.derToRaw(s.sign()));
    }
}
