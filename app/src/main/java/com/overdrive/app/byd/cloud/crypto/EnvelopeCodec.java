package com.overdrive.app.byd.cloud.crypto;

import java.io.IOException;
import java.io.InputStream;

/**
 * Common contract for the BYD cloud transport envelope codec.
 *
 * Two implementations exist, selected by region:
 *   - {@link BangcleCodec} — overseas/international ({@code dilinkappoversea-*.byd.auto})
 *   - {@link WbskCodec}     — China ({@code *-cn.byd.auto})
 *
 * Both encode an outer-payload JSON string into the wire envelope and decode the
 * response envelope back to a JSON string. The transport holds one instance of
 * this type and is otherwise region-agnostic.
 *
 * Port of: pyBYD/src/pybyd/_transport.py (EnvelopeCodec Protocol)
 */
public interface EnvelopeCodec {

    /** Load the white-box lookup tables from a stream (APK asset or cache). Idempotent. */
    void loadTables(InputStream is) throws IOException;

    /** Whether the tables have been loaded and the codec is ready to encode/decode. */
    boolean isReady();

    /** Encode an outer-payload JSON string into the wire envelope. */
    String encodeEnvelope(String plaintext);

    /** Decode a response envelope back to its plaintext JSON string. */
    String decodeEnvelope(String envelope);
}
