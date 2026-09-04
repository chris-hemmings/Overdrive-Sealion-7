package com.overdrive.app.byd.cloud.crypto;

import java.nio.charset.StandardCharsets;

/**
 * WBSK white-box AES block transform + CBC + double-nested envelope.
 *
 * Port of: pyBYD/src/pybyd/_crypto/wbsk.py
 * Source : AwangYes/BYD-re wbsk.js (white-box AES from the CN DiLink
 *          {@code libwbsk_crypto_tool.so}).
 *
 * The key is baked into the lookup tables ({@link WbskTables}); the "key blob"
 * passed to {@link #parseWbcKey} only carries the round-key schedule and the
 * mode (which fixes key size / rounds / block size). The envelope is two
 * independent CBC layers (inner then outer) with nibble-level input/output
 * obfuscation and an obfuscated PKCS7 pad — see {@link #encryptEnvelope}.
 *
 * Pure Java (no android.*) so it runs identically on-device and in the
 * off-device golden-vector oracle.
 */
public final class WbskBlockCipher {

    private WbskBlockCipher() {}

    // ── Nibble permutation tables (wbsk.py NIBBLE_ENCODE / NIBBLE_DECODE) ──
    private static final int[] NIBBLE_ENCODE =
            {0x0, 0x8, 0x4, 0xC, 0x1, 0x9, 0x5, 0xD, 0x2, 0xA, 0x6, 0xE, 0x3, 0xB, 0x7, 0xF};
    private static final int[] NIBBLE_DECODE =
            {0x0, 0x4, 0x8, 0xC, 0x2, 0x6, 0xA, 0xE, 0x1, 0x5, 0x9, 0xD, 0x3, 0x7, 0xB, 0xF};
    // MYSTERY_ENCODE[n] = NIBBLE_ENCODE[NIBBLE_ENCODE[n ^ 8]]
    private static final int[] MYSTERY_ENCODE = new int[16];
    static {
        for (int n = 0; n < 16; n++) {
            MYSTERY_ENCODE[n] = NIBBLE_ENCODE[NIBBLE_ENCODE[n ^ 8]];
        }
    }

    // Shift-row index arrays
    private static final int[] SR  = {0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11};
    private static final int[] INV_SR = {0, 13, 10, 7, 4, 1, 14, 11, 8, 5, 2, 15, 12, 9, 6, 3};
    // Per-round T-table column index lists
    private static final int[] TE1I = {5, 9, 13, 1};
    private static final int[] TE2I = {10, 14, 2, 6};
    private static final int[] TE3I = {15, 3, 7, 11};
    private static final int[] TD1I = {13, 1, 5, 9};
    private static final int[] TD2I = {10, 14, 2, 6};
    private static final int[] TD3I = {7, 11, 15, 3};

    // ── Parsed key blob ─────────────────────────────────────────────────

    /** Result of {@link #parseWbcKey}: round-key data + cipher parameters. */
    public static final class ParsedWbcKey {
        public final int[] keyData;   // round-key bytes (unmasked), values 0..255
        public final int keySizeBits;
        public final int numRounds;
        public final int isDecrypt;
        public final int blockSize;
        public final int mode;

        ParsedWbcKey(int[] keyData, int keySizeBits, int numRounds, int isDecrypt, int blockSize, int mode) {
            this.keyData = keyData;
            this.keySizeBits = keySizeBits;
            this.numRounds = numRounds;
            this.isDecrypt = isDecrypt;
            this.blockSize = blockSize;
            this.mode = mode;
        }
    }

    /** prot_xor: protected nibble-wise XOR through a 256-entry table. */
    private static int protXor(int[] table, int a, int b) {
        int hi = table[(((a >> 4) << 4) ^ (b >> 4)) & 0xFF] & 0xF0;
        int lo = (table[(((a & 0xF) << 4) ^ (b & 0xF)) & 0xFF] >> 4) & 0x0F;
        return hi | lo;
    }

    /** Parse a hex key blob: derive mode, key size, round count, round keys. */
    public static ParsedWbcKey parseWbcKey(String hexStr) {
        int[] raw = hexToInts(hexStr);
        if (raw.length < 5) {
            throw new IllegalArgumentException("WBC key blob too short: " + raw.length + " bytes");
        }
        int mode = raw[0] ^ raw[3];
        int[] keyData = new int[raw.length - 4];
        for (int i = 4; i < raw.length; i++) {
            keyData[i - 4] = raw[i] ^ raw[i % 3];
        }

        int keySizeBits;
        switch (mode) {
            case 0: case 1:        keySizeBits = 0x80; break;
            case 2: case 3:        keySizeBits = 0xC0; break;
            case 4: case 5:        keySizeBits = 0x80; break;
            case 6: case 7:        keySizeBits = 0x40; break;
            case 8: case 9:        keySizeBits = 0xC0; break;
            case 0xA: case 0xB:    keySizeBits = 0x80; break;
            case 0xC: case 0xD:    keySizeBits = 0x80; break;
            case 0xE: case 0xF:    keySizeBits = 0xC0; break;
            case 0x10: case 0x11:  keySizeBits = 0x100; break;
            case 0x12: case 0x13:  keySizeBits = 0x40; break;
            case 0x14: case 0x15:  keySizeBits = 0xC0; break;
            case 0x16: case 0x17:  keySizeBits = 0x80; break;
            default:
                throw new IllegalArgumentException("Unknown WBC mode: 0x" + Integer.toHexString(mode));
        }
        int numRounds = (keySizeBits >> 5) + 6;
        int isDecrypt = mode & 1;
        int blockSize = (mode == 6 || mode == 7 || mode == 0x12 || mode == 0x13) ? 8 : 16;
        return new ParsedWbcKey(keyData, keySizeBits, numRounds, isDecrypt, blockSize, mode);
    }

    // ── Block transforms ────────────────────────────────────────────────

    public static byte[] encryptBlock(WbskTables t, byte[] in, int[] keyData, int numRounds) {
        int[] state = new int[16];
        int[] temp1 = new int[16];
        int[] temp2 = new int[16];
        for (int i = 0; i < 16; i++) {
            state[i] = protXor(t.encInitXor, in[i] & 0xFF, keyData[i]);
        }
        for (int r = 1; r < numRounds; r++) {
            for (int c = 0; c < 4; c++) {
                int v = t.encTe0[state[c * 4]];
                temp1[c * 4]     = (v >>> 24) & 0xFF;
                temp1[c * 4 + 1] = (v >>> 16) & 0xFF;
                temp1[c * 4 + 2] = (v >>> 8) & 0xFF;
                temp1[c * 4 + 3] = v & 0xFF;
            }
            for (int c = 0; c < 4; c++) {
                int v = t.encTe1[state[TE1I[c]]];
                temp2[c * 4]     = (v >>> 24) & 0xFF;
                temp2[c * 4 + 1] = (v >>> 16) & 0xFF;
                temp2[c * 4 + 2] = (v >>> 8) & 0xFF;
                temp2[c * 4 + 3] = v & 0xFF;
            }
            for (int i = 0; i < 16; i++) temp1[i] = protXor(t.encRoundXor, temp1[i], temp2[i]);
            for (int c = 0; c < 4; c++) {
                int v = t.encTe2[state[TE2I[c]]];
                temp2[c * 4]     = (v >>> 24) & 0xFF;
                temp2[c * 4 + 1] = (v >>> 16) & 0xFF;
                temp2[c * 4 + 2] = (v >>> 8) & 0xFF;
                temp2[c * 4 + 3] = v & 0xFF;
            }
            for (int i = 0; i < 16; i++) temp1[i] = protXor(t.encRoundXor, temp1[i], temp2[i]);
            for (int c = 0; c < 4; c++) {
                int v = t.encTe3[state[TE3I[c]]];
                temp2[c * 4]     = (v >>> 24) & 0xFF;
                temp2[c * 4 + 1] = (v >>> 16) & 0xFF;
                temp2[c * 4 + 2] = (v >>> 8) & 0xFF;
                temp2[c * 4 + 3] = v & 0xFF;
            }
            for (int i = 0; i < 16; i++) temp1[i] = protXor(t.encRoundXor, temp1[i], temp2[i]);
            int rkOff = r * 16;
            for (int i = 0; i < 16; i++) {
                state[i] = protXor(t.encRoundXor, temp1[i], keyData[rkOff + i]);
            }
        }
        for (int i = 0; i < 16; i++) temp1[i] = t.encSbox[state[SR[i]]];
        byte[] output = new byte[16];
        int frkOff = numRounds * 16;
        for (int i = 0; i < 16; i++) {
            output[i] = (byte) protXor(t.encFinalXor, temp1[i], keyData[frkOff + i]);
        }
        return output;
    }

    public static byte[] decryptBlock(WbskTables t, byte[] in, int[] keyData, int numRounds) {
        int[] state = new int[16];
        int[] temp1 = new int[16];
        int[] temp2 = new int[16];
        for (int i = 0; i < 16; i++) {
            state[i] = protXor(t.decInitXor, in[i] & 0xFF, keyData[i]);
        }
        for (int r = 1; r < numRounds; r++) {
            for (int c = 0; c < 4; c++) {
                int v = t.decTd0[state[c * 4]];
                temp1[c * 4]     = (v >>> 24) & 0xFF;
                temp1[c * 4 + 1] = (v >>> 16) & 0xFF;
                temp1[c * 4 + 2] = (v >>> 8) & 0xFF;
                temp1[c * 4 + 3] = v & 0xFF;
            }
            for (int c = 0; c < 4; c++) {
                int v = t.decTd1[state[TD1I[c]]];
                temp2[c * 4]     = (v >>> 24) & 0xFF;
                temp2[c * 4 + 1] = (v >>> 16) & 0xFF;
                temp2[c * 4 + 2] = (v >>> 8) & 0xFF;
                temp2[c * 4 + 3] = v & 0xFF;
            }
            for (int i = 0; i < 16; i++) temp1[i] = protXor(t.decRoundXor, temp1[i], temp2[i]);
            for (int c = 0; c < 4; c++) {
                int v = t.decTd2[state[TD2I[c]]];
                temp2[c * 4]     = (v >>> 24) & 0xFF;
                temp2[c * 4 + 1] = (v >>> 16) & 0xFF;
                temp2[c * 4 + 2] = (v >>> 8) & 0xFF;
                temp2[c * 4 + 3] = v & 0xFF;
            }
            for (int i = 0; i < 16; i++) temp1[i] = protXor(t.decRoundXor, temp1[i], temp2[i]);
            for (int c = 0; c < 4; c++) {
                int v = t.decTd3[state[TD3I[c]]];
                temp2[c * 4]     = (v >>> 24) & 0xFF;
                temp2[c * 4 + 1] = (v >>> 16) & 0xFF;
                temp2[c * 4 + 2] = (v >>> 8) & 0xFF;
                temp2[c * 4 + 3] = v & 0xFF;
            }
            for (int i = 0; i < 16; i++) temp1[i] = protXor(t.decRoundXor, temp1[i], temp2[i]);
            int rkOff = r * 16;
            for (int i = 0; i < 16; i++) {
                state[i] = protXor(t.decRoundXor, temp1[i], keyData[rkOff + i]);
            }
        }
        for (int i = 0; i < 16; i++) temp1[i] = t.decInvSbox[state[INV_SR[i]]];
        byte[] output = new byte[16];
        int frkOff = numRounds * 16;
        for (int i = 0; i < 16; i++) {
            output[i] = (byte) protXor(t.decFinalXor, temp1[i], keyData[frkOff + i]);
        }
        return output;
    }

    // ── CBC wrappers ────────────────────────────────────────────────────

    public static byte[] encryptCbc(WbskTables t, byte[] plaintext, int[] keyData, int numRounds, byte[] iv) {
        int blockCount = plaintext.length / 16;
        byte[] output = new byte[plaintext.length];
        byte[] prev = iv;
        for (int b = 0; b < blockCount; b++) {
            byte[] block = new byte[16];
            for (int i = 0; i < 16; i++) {
                block[i] = (byte) ((plaintext[b * 16 + i] & 0xFF) ^ (prev[i] & 0xFF));
            }
            byte[] enc = encryptBlock(t, block, keyData, numRounds);
            System.arraycopy(enc, 0, output, b * 16, 16);
            prev = enc;
        }
        return output;
    }

    public static byte[] decryptCbc(WbskTables t, byte[] ciphertext, int[] keyData, int numRounds, byte[] iv) {
        int blockCount = ciphertext.length / 16;
        byte[] output = new byte[ciphertext.length];
        byte[] prev = iv;
        for (int b = 0; b < blockCount; b++) {
            byte[] block = new byte[16];
            System.arraycopy(ciphertext, b * 16, block, 0, 16);
            byte[] dec = decryptBlock(t, block, keyData, numRounds);
            for (int i = 0; i < 16; i++) {
                output[b * 16 + i] = (byte) ((dec[i] & 0xFF) ^ (prev[i] & 0xFF));
            }
            prev = block;
        }
        return output;
    }

    // ── Nibble / padding transforms ─────────────────────────────────────

    static byte[] nibbleEncode(byte[] buf) {
        byte[] out = new byte[buf.length];
        for (int i = 0; i < buf.length; i++) {
            int b = buf[i] & 0xFF;
            out[i] = (byte) ((NIBBLE_ENCODE[b >> 4] << 4) | NIBBLE_ENCODE[b & 0xF]);
        }
        return out;
    }

    static byte[] nibbleDecode(byte[] buf) {
        byte[] out = new byte[buf.length];
        for (int i = 0; i < buf.length; i++) {
            int b = buf[i] & 0xFF;
            out[i] = (byte) ((NIBBLE_DECODE[b >> 4] << 4) | NIBBLE_DECODE[b & 0xF]);
        }
        return out;
    }

    static byte[] wbcInputEncode(byte[] buf) {
        byte[] out = new byte[buf.length];
        for (int i = 0; i < buf.length; i++) {
            int b = buf[i] & 0xFF;
            out[i] = (byte) ((MYSTERY_ENCODE[b >> 4] << 4) | MYSTERY_ENCODE[b & 0xF]);
        }
        return out;
    }

    static byte[] wbcOutputDecode(byte[] buf) {
        byte[] out = new byte[buf.length];
        for (int i = 0; i < buf.length; i++) {
            int b = buf[i] & 0xFF;
            int hi = NIBBLE_ENCODE[NIBBLE_ENCODE[b >> 4]];
            int lo = NIBBLE_ENCODE[NIBBLE_ENCODE[b & 0xF]];
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static byte[] stripPkcs7(byte[] buf) {
        if (buf.length == 0) return buf;
        int padVal = buf[buf.length - 1] & 0xFF;
        if (padVal < 1 || padVal > 16) return buf;
        for (int i = buf.length - padVal; i < buf.length; i++) {
            if ((buf[i] & 0xFF) != padVal) return buf;
        }
        byte[] out = new byte[buf.length - padVal];
        System.arraycopy(buf, 0, out, 0, out.length);
        return out;
    }

    /** PKCS7 where the pad byte VALUE is itself MYSTERY-encoded (wbsk.py add_wbc_pkcs7). */
    static byte[] addWbcPkcs7(byte[] buf, int blockSize) {
        int remainder = buf.length % blockSize;
        int padN = (remainder == 0) ? blockSize : blockSize - remainder;
        int padByte = (MYSTERY_ENCODE[padN >> 4] << 4) | MYSTERY_ENCODE[padN & 0xF];
        byte[] out = new byte[buf.length + padN];
        System.arraycopy(buf, 0, out, 0, buf.length);
        for (int i = buf.length; i < out.length; i++) {
            out[i] = (byte) padByte;
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] zeros(int n) {
        return new byte[n];
    }

    // ── Envelope ────────────────────────────────────────────────────────

    /**
     * Encrypt a UTF-8 plaintext into the WBSK double-nested CBC envelope
     * (base64, no prefix). Mirrors wbsk.py encrypt_wbsk_envelope.
     */
    public static String encryptEnvelope(WbskTables t,
                                         String plaintext,
                                         String innerEncKeyHex, String innerEncIvHex,
                                         String outerEncKeyHex, String outerEncIvHex) {
        byte[] plainBuf = plaintext.getBytes(StandardCharsets.UTF_8);

        byte[] innerPadded = addWbcPkcs7(wbcInputEncode(plainBuf), 16);
        ParsedWbcKey innerKey = parseWbcKey(innerEncKeyHex);
        byte[] innerIv = hexToBytes(innerEncIvHex);
        byte[] innerEncrypted = encryptCbc(t, innerPadded, innerKey.keyData, innerKey.numRounds, innerIv);
        byte[] innerRaw = wbcOutputDecode(innerEncrypted);
        String innerB64 = base64Encode(innerRaw);

        // outer content = inner_b64 (latin-1 bytes) ++ wbcOutputDecode(innerIv)
        byte[] outerContentPlain = concat(latin1(innerB64), wbcOutputDecode(innerIv));
        byte[] outerMystery = addWbcPkcs7(wbcInputEncode(outerContentPlain), 16);
        ParsedWbcKey outerKey = parseWbcKey(outerEncKeyHex);
        byte[] outerIv = hexToBytes(outerEncIvHex);
        byte[] outerEncrypted = encryptCbc(t, outerMystery, outerKey.keyData, outerKey.numRounds, outerIv);
        return base64Encode(wbcOutputDecode(outerEncrypted));
    }

    /**
     * Decrypt a WBSK envelope back to its UTF-8 plaintext. Mirrors wbsk.py
     * decrypt_wbsk_envelope. The inner CBC IV is recovered from the tail of the
     * outer payload (there is no separate innerDecryptIv).
     */
    public static String decryptEnvelope(WbskTables t,
                                         String base64Str,
                                         String outerKeyHex, String innerKeyHex,
                                         String outerSessionIvHex) {
        byte[] raw = base64Decode(base64Str.trim());
        byte[] outerEncoded = concat(nibbleEncode(raw), zeros(256));
        ParsedWbcKey outerKey = parseWbcKey(outerKeyHex);
        byte[] outerIv = hexToBytes(outerSessionIvHex);
        byte[] outerDecrypted = decryptCbc(t, outerEncoded, outerKey.keyData, outerKey.numRounds, outerIv);

        byte[] outerRange = new byte[raw.length];
        System.arraycopy(outerDecrypted, 0, outerRange, 0, raw.length);
        byte[] outerContent = stripPkcs7(nibbleDecode(outerRange));
        int contentLen = outerContent.length;

        // inner_b64 = outer_content[: len-16]; inner_iv = outer_decrypted[len-16 : len]
        byte[] innerB64Bytes = new byte[contentLen - 16];
        System.arraycopy(outerContent, 0, innerB64Bytes, 0, contentLen - 16);
        String innerB64 = new String(innerB64Bytes, StandardCharsets.ISO_8859_1);
        byte[] innerIv = new byte[16];
        System.arraycopy(outerDecrypted, contentLen - 16, innerIv, 0, 16);

        byte[] innerRaw = base64Decode(innerB64);
        byte[] innerEncoded = concat(nibbleEncode(innerRaw), zeros(256));
        ParsedWbcKey innerKey = parseWbcKey(innerKeyHex);
        byte[] innerDecrypted = decryptCbc(t, innerEncoded, innerKey.keyData, innerKey.numRounds, innerIv);

        byte[] innerRange = new byte[innerRaw.length];
        System.arraycopy(innerDecrypted, 0, innerRange, 0, innerRaw.length);
        byte[] innerContent = stripPkcs7(nibbleDecode(innerRange));
        return new String(innerContent, StandardCharsets.UTF_8);
    }

    // ── Hex / Base64 (pure Java, no android.util.Base64) ────────────────

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static int[] hexToInts(String hex) {
        int len = hex.length();
        int[] out = new int[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16);
        }
        return out;
    }

    static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64Encode(byte[] data) {
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        int n = data.length;
        while (i + 3 <= n) {
            int b = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(B64[(b >> 18) & 0x3F]).append(B64[(b >> 12) & 0x3F])
              .append(B64[(b >> 6) & 0x3F]).append(B64[b & 0x3F]);
            i += 3;
        }
        int rem = n - i;
        if (rem == 1) {
            int b = (data[i] & 0xFF) << 16;
            sb.append(B64[(b >> 18) & 0x3F]).append(B64[(b >> 12) & 0x3F]).append("==");
        } else if (rem == 2) {
            int b = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(B64[(b >> 18) & 0x3F]).append(B64[(b >> 12) & 0x3F])
              .append(B64[(b >> 6) & 0x3F]).append("=");
        }
        return sb.toString();
    }

    static byte[] base64Decode(String s) {
        // Normalize: drop whitespace, map URL-safe alphabet, fix padding.
        StringBuilder cleaned = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (c == '-') c = '+';
            else if (c == '_') c = '/';
            cleaned.append(c);
        }
        int rem = cleaned.length() % 4;
        if (rem != 0) {
            for (int i = 0; i < 4 - rem; i++) cleaned.append('=');
        }
        String str = cleaned.toString();

        int[] inv = B64_INV;
        int padding = 0;
        int len = str.length();
        if (len >= 1 && str.charAt(len - 1) == '=') padding++;
        if (len >= 2 && str.charAt(len - 2) == '=') padding++;
        int outLen = (len / 4) * 3 - padding;
        byte[] out = new byte[outLen];
        int oi = 0;
        for (int i = 0; i < len; i += 4) {
            int c0 = inv[str.charAt(i)];
            int c1 = inv[str.charAt(i + 1)];
            int c2 = str.charAt(i + 2) == '=' ? 0 : inv[str.charAt(i + 2)];
            int c3 = str.charAt(i + 3) == '=' ? 0 : inv[str.charAt(i + 3)];
            int b = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
            if (oi < outLen) out[oi++] = (byte) ((b >> 16) & 0xFF);
            if (oi < outLen) out[oi++] = (byte) ((b >> 8) & 0xFF);
            if (oi < outLen) out[oi++] = (byte) (b & 0xFF);
        }
        return out;
    }

    private static final int[] B64_INV = new int[128];
    static {
        for (int i = 0; i < B64_INV.length; i++) B64_INV[i] = 0;
        for (int i = 0; i < B64.length; i++) B64_INV[B64[i]] = i;
    }
}
