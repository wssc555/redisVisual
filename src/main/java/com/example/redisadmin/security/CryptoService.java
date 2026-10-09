package com.example.redisadmin.security;

import com.example.redisadmin.storage.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 对称加密。用于 Redis 连接凭据的静态加密（落库为密文）。
 * <p>密钥文件 {@code <data>/app.key}：存在即用，缺失即生成（32 字节随机）。
 * 密钥与数据库文件同目录，必须一起备份——丢失密钥等于全部密码不可解。</p>
 * <p>密文格式：{@code base64(iv[12] || ciphertext+tag)}。</p>
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec secretKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(AppPaths appPaths) {
        Path keyFile = appPaths.getKeyFilePath();
        byte[] key = loadOrCreateKey(keyFile);
        this.secretKey = new SecretKeySpec(key, "AES");
        log.info("凭据加密密钥已就绪（文件 {}，{} 字节）", keyFile.getFileName(), key.length);
    }

    private byte[] loadOrCreateKey(Path keyFile) {
        try {
            if (Files.exists(keyFile)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                if (raw.length != KEY_LENGTH) {
                    throw new IllegalStateException("密钥文件长度非法（期望 " + KEY_LENGTH + " 字节）: " + keyFile);
                }
                return raw;
            }
            Files.createDirectories(keyFile.getParent());
            byte[] key = new byte[KEY_LENGTH];
            new SecureRandom().nextBytes(key);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardCharsets.UTF_8);
            log.warn("已生成新的凭据加密密钥文件: {}（请与数据库文件一同备份）", keyFile);
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("密钥文件读写失败: " + keyFile, e);
        }
    }

    /**
     * 加密为 base64 密文。
     */
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败", e);
        }
    }

    /**
     * 解密 base64 密文。密文损坏或密钥不匹配时抛错。
     */
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText);
            if (payload.length <= IV_LENGTH) {
                throw new IllegalStateException("密文长度非法");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("凭据解密失败（密钥是否与数据库匹配？）", e);
        }
    }
}
