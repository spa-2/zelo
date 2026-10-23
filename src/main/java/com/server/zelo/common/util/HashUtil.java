package com.server.zelo.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class HashUtil {

    private final String algorithm;

    public HashUtil(@Value("${zelo.hash.algorithm}") String algorithm) {
        this.algorithm = algorithm;
    }

    // 토큰 원문은 저장하지 않고 해시 처리하여서 redis가 유출 되어도 토큰 탈취가 되지 않음
    public String hash(String refreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 지원해야 하는 알고리즘이라 여기 올 수 없지만 언체크라서 예외를 잡긴 해야됨
            throw new IllegalStateException(e);
        }
    }
}
