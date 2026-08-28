package com.localtools.comicviewer.data;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.localtools.comicviewer.util.InputLimits;

/** Two-stage duplicate check: cheap prefix sampling, then full hashing only on a collision. */
public final class ContentFingerprint {
    private static final int SAMPLE_BYTES = 256 * 1024;

    private ContentFingerprint() {
    }

    public static String sample(Context context, Uri uri, long size) throws IOException {
        InputLimits.validateDocumentSize(size);
        MessageDigest digest = sha256();
        digest.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("The document cannot be sampled.");
            digest.update(InputLimits.readPrefix(raw, SAMPLE_BYTES));
            return hex(digest.digest());
        } catch (SecurityException exception) {
            throw new IOException("The document cannot be sampled.", exception);
        }
    }

    public static String full(Context context, Uri uri) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("The document cannot be fingerprinted.");
            OutputStream digestSink = new OutputStream() {
                @Override
                public void write(int value) {
                    digest.update((byte) value);
                }

                @Override
                public void write(byte[] buffer, int offset, int length) {
                    digest.update(buffer, offset, length);
                }
            };
            InputLimits.copy(raw, digestSink, InputLimits.MAX_DOCUMENT_BYTES,
                    InputLimits.Reason.DOCUMENT_BYTES);
            return hex(digest.digest());
        } catch (SecurityException exception) {
            throw new IOException("The document cannot be fingerprinted.", exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
