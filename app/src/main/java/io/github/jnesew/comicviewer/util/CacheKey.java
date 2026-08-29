package io.github.jnesew.comicviewer.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Stable, compact names for private cache files. */
public final class CacheKey {
    private CacheKey() {
    }

    public static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                result.append(String.format(Locale.ROOT, "%02x", bytes[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
