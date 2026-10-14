package dev.air.browser;

import dev.air.updates.UpdateInfo;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

final class UpdateManifest {
    private UpdateManifest() {}

    static UpdateInfo read(byte[] bytes) throws JSONException, CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        JSONObject json = new JSONObject(text);
        if (number(json, "schemaVersion") != 1
                || !UpdateInfo.APPLICATION_ID.equals(string(json, "applicationId"))
                || !"arm64-v8a".equals(string(json, "abi"))) throw new JSONException("Unsupported AIR update manifest");
        long sdk = number(json, "minSdk");
        if (sdk < 29 || sdk > 10_000) throw new JSONException("Invalid minimum SDK");
        try {
            return new UpdateInfo(number(json, "versionCode"), string(json, "versionName"), (int) sdk,
                    string(json, "apkUrl"), string(json, "sha256"), number(json, "sizeBytes"));
        } catch (IllegalArgumentException invalid) { throw new JSONException("Invalid AIR update manifest"); }
    }

    private static String string(JSONObject json, String name) throws JSONException {
        Object value = json.get(name);
        if (!(value instanceof String)) throw new JSONException("Expected string: " + name);
        return (String) value;
    }
    private static long number(JSONObject json, String name) throws JSONException {
        Object value = json.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new JSONException("Expected integer: " + name);
        return ((Number) value).longValue();
    }
}
