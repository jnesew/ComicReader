import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** Build-time key inspection only. Secrets enter through stdin and never leave this process. */
class SigningKeyProbe {
    public static void main(String[] arguments) {
        try {
            inspect(arguments);
        } catch (Exception failure) {
            // Provider exceptions can contain file or key details; return a fixed diagnostic.
            System.err.println("Cannot unlock or inspect the signing keystore.");
            System.exit(1);
        }
    }

    private static void inspect(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        char[] storeSecret = line(input);
        char[] keySecret = line(input);
        try {
            KeyStore store = KeyStore.getInstance(new File(arguments[0]), storeSecret);
            List<String> aliases = new ArrayList<>(arguments[1].isEmpty()
                    ? Collections.list(store.aliases()) : List.of(arguments[1]));
            Collections.sort(aliases);
            List<String> entries = new ArrayList<>();
            for (String alias : aliases) {
                Certificate certificate = store.getCertificate(alias);
                if (!store.isKeyEntry(alias) || certificate == null) continue;
                boolean unlocked;
                try {
                    unlocked = store.getKey(alias, keySecret) instanceof PrivateKey;
                } catch (UnrecoverableKeyException differentPassword) {
                    unlocked = false;
                }
                String fingerprint = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                entries.add("{\"alias\":" + quote(alias) + ",\"certificate_sha256\":\""
                        + fingerprint + "\",\"key_unlocked\":" + unlocked + "}");
            }
            System.out.println("{\"entries\":[" + String.join(",", entries) + "]}");
        } finally {
            Arrays.fill(storeSecret, '\0');
            Arrays.fill(keySecret, '\0');
        }
    }

    private static char[] line(BufferedReader input) throws Exception {
        String value = input.readLine();
        if (value == null || value.isEmpty()) throw new IllegalArgumentException();
        return value.toCharArray();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            if (character == '\\' || character == '"') result.append('\\').append(character);
            else if (character < 32 || character > 126) result.append(String.format("\\u%04x", (int) character));
            else result.append(character);
        }
        return result.append('"').toString();
    }
}
