package com.overdrive.app.byd.cloud.crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WBSK white-box AES lookup tables (China BYD cloud transport layer).
 *
 * 16 tables from the CN cloud transport's {@code libwbsk_crypto_tool.so}:
 *   - 8 byte tables  (256 bytes each): enc/dec InitXor, RoundXor, Sbox/InvSbox, FinalXor
 *   - 8 u32  tables (1024 bytes each, little-endian): enc Te0..Te3, dec Td0..Td3
 *
 * Ships as the APK asset {@code byd/wbsk_tables.json} — a flat JSON object of
 * name -> base64 string. This is the CN counterpart of {@link BangcleTables};
 * WBSK uses JSON (matching pyBYD) rather than the BGTB binary format because the
 * source library lays the tables out as plain base64 slices.
 *
 * Port of: pyBYD/src/pybyd/_crypto/wbsk.py (WbskTables)
 * Tables : AwangYes/BYD-re wbsk_tables.js (verified byte-for-byte against the
 *          BYD-re golden encrypt/decrypt vectors).
 *
 * Pure Java (no android.*, no org.json) so the crypto core can be unit-tested
 * off-device against the golden vectors with zero drift from the shipped code.
 */
public final class WbskTables {

    private static final int BYTE_TABLE_LEN = 256;
    private static final int U32_TABLE_LEN = 1024; // 256 * 4 bytes, little-endian

    // Encrypt byte tables
    public final int[] encInitXor;
    public final int[] encRoundXor;
    public final int[] encSbox;
    public final int[] encFinalXor;
    // Encrypt T-tables (256 u32 each)
    public final int[] encTe0;
    public final int[] encTe1;
    public final int[] encTe2;
    public final int[] encTe3;
    // Decrypt byte tables
    public final int[] decInitXor;
    public final int[] decRoundXor;
    public final int[] decInvSbox;
    public final int[] decFinalXor;
    // Decrypt T-tables (256 u32 each)
    public final int[] decTd0;
    public final int[] decTd1;
    public final int[] decTd2;
    public final int[] decTd3;

    /**
     * Build from a map of table-name -> base64 string.
     *
     * @throws IllegalArgumentException if any table is missing or the wrong size.
     */
    public WbskTables(Map<String, String> encoded) {
        encInitXor  = byteTable(encoded, "encInitXor");
        encRoundXor = byteTable(encoded, "encRoundXor");
        encSbox     = byteTable(encoded, "encSbox");
        encFinalXor = byteTable(encoded, "encFinalXor");
        encTe0 = u32Table(encoded, "encTe0");
        encTe1 = u32Table(encoded, "encTe1");
        encTe2 = u32Table(encoded, "encTe2");
        encTe3 = u32Table(encoded, "encTe3");
        decInitXor  = byteTable(encoded, "decInitXor");
        decRoundXor = byteTable(encoded, "decRoundXor");
        decInvSbox  = byteTable(encoded, "decInvSbox");
        decFinalXor = byteTable(encoded, "decFinalXor");
        decTd0 = u32Table(encoded, "decTd0");
        decTd1 = u32Table(encoded, "decTd1");
        decTd2 = u32Table(encoded, "decTd2");
        decTd3 = u32Table(encoded, "decTd3");
    }

    // ── Loaders ─────────────────────────────────────────────────────────

    /** Parse a {@code {"name":"base64", ...}} JSON object into the table map. */
    public static WbskTables fromJsonString(String json) {
        return new WbskTables(parseFlatJsonObject(json));
    }

    /** Read all bytes from a stream as UTF-8 JSON and build the tables. */
    public static WbskTables loadFromStream(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return fromJsonString(new String(bos.toByteArray(), StandardCharsets.UTF_8));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static int[] byteTable(Map<String, String> encoded, String name) {
        byte[] raw = decode(encoded, name);
        if (raw.length != BYTE_TABLE_LEN) {
            throw new IllegalArgumentException(
                    "WBSK table " + name + " has unexpected size " + raw.length + " (expected 256)");
        }
        int[] out = new int[BYTE_TABLE_LEN];
        for (int i = 0; i < BYTE_TABLE_LEN; i++) {
            out[i] = raw[i] & 0xFF;
        }
        return out;
    }

    private static int[] u32Table(Map<String, String> encoded, String name) {
        byte[] raw = decode(encoded, name);
        if (raw.length != U32_TABLE_LEN) {
            throw new IllegalArgumentException(
                    "WBSK table " + name + " has unexpected size " + raw.length + " (expected 1024)");
        }
        int[] out = new int[256];
        for (int i = 0; i < 256; i++) {
            int o = i * 4;
            // little-endian uint32 -> int (bit pattern preserved)
            out[i] = (raw[o] & 0xFF)
                    | ((raw[o + 1] & 0xFF) << 8)
                    | ((raw[o + 2] & 0xFF) << 16)
                    | ((raw[o + 3] & 0xFF) << 24);
        }
        return out;
    }

    private static byte[] decode(Map<String, String> encoded, String name) {
        String b64 = encoded.get(name);
        if (b64 == null || b64.isEmpty()) {
            throw new IllegalArgumentException("Missing embedded WBSK table: " + name);
        }
        return WbskBlockCipher.base64Decode(b64);
    }

    /**
     * Minimal flat-JSON-object parser for {@code {"key":"value", ...}} where
     * every value is a base64 string (no nested objects, no escapes). The WBSK
     * tables file is generated and tightly controlled, so this is sufficient and
     * avoids pulling org.json into the pure-Java crypto core.
     */
    private static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> map = new HashMap<>();
        Matcher m = KV.matcher(json);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException("wbsk_tables.json contained no entries");
        }
        return map;
    }

    // base64 values never contain '"' or '\', so [^"]* is a safe value capture
    private static final Pattern KV =
            Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
}
