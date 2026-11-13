package com.qixing.hud.phone;

/** Minimal WAV reader for TTS output: 16-bit PCM, mixed down to mono, optionally downsampled. */
final class Wav {
    static final class Pcm {
        int rate;
        byte[] data; // 16-bit little-endian mono

        Pcm(int rate, byte[] data) {
            this.rate = rate;
            this.data = data;
        }
    }

    private Wav() {}

    static Pcm parse(byte[] b) {
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return null;
        int pos = 12, channels = 0, rate = 0, bits = 0, fmt = 0;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int len = le32(b, pos + 4);
            int body = pos + 8;
            // Some engines leave the data size as a 0xFFFFFFFF placeholder: take the rest of the file.
            if (len < 0 && "data".equals(id)) len = b.length - body;
            if (len < 0) return null;
            if ("fmt ".equals(id) && body + 16 <= b.length) {
                fmt = le16(b, body);
                channels = le16(b, body + 2);
                rate = le32(b, body + 4);
                bits = le16(b, body + 14);
            } else if ("data".equals(id)) {
                if (fmt != 1 || bits != 16 || channels < 1 || rate <= 0) return null;
                int n = Math.min(len, b.length - body);
                int frames = n / (2 * channels);
                byte[] mono = new byte[frames * 2];
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) {
                        int o = body + (i * channels + c) * 2;
                        sum += (short) ((b[o] & 0xff) | (b[o + 1] << 8));
                    }
                    int v = sum / channels;
                    mono[i * 2] = (byte) v;
                    mono[i * 2 + 1] = (byte) (v >> 8);
                }
                return new Pcm(rate, mono);
            }
            pos = body + len + (len & 1);
        }
        return null;
    }

    /** Halves the sample rate (averaging pairs) while it stays at or above minRate. */
    static Pcm shrink(Pcm p, int minRate) {
        while (p.rate / 2 >= minRate) {
            int frames = p.data.length / 2 / 2;
            byte[] out = new byte[frames * 2];
            for (int i = 0; i < frames; i++) {
                int a = (short) ((p.data[i * 4] & 0xff) | (p.data[i * 4 + 1] << 8));
                int c = (short) ((p.data[i * 4 + 2] & 0xff) | (p.data[i * 4 + 3] << 8));
                int v = (a + c) / 2;
                out[i * 2] = (byte) v;
                out[i * 2 + 1] = (byte) (v >> 8);
            }
            p = new Pcm(p.rate / 2, out);
        }
        return p;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }
}
