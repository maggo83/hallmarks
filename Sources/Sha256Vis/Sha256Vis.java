// Sha256Vis — experimental visual identity from a SHA-256 hash.
// SPDX-License-Identifier: MIT
//
// Pipeline:
//   1. sha256(input) → 32 bytes hb[0..31]
//   2. For i in 0..3:  c[i] = CRC-8/SMBUS(hb[i], hb[i+4], hb[i+8], …, hb[i+28])
//   3. c[0]  → hue (top 4 bits)  + chroma offset (bottom 4 bits)
//      c[1] + c[2] + top 5 bits of c[3]  → 21 cell bits  (7 rows × 3 cols)
//      bit 2 of c[3]                     → flip bit (swaps fg/bg luminance)
//      bits 1..0 of c[3]                 → base-luminance index
//   4. Mirror 7×3 grid around centre column → 7×5 cells (on/off only).

package sha256vis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Sha256Vis {

    private Sha256Vis() {}

    // =========================================================================
    // Public types
    // =========================================================================

    public enum Style { STANDARD, HIGH_CONTRAST, MONOCHROME }

    public record OklchColor(double L, double C, double h, String hex) {}

    /** 7×5 on/off grid plus the two paint colours and verbal companion words. */
    public record VisSpec(
            int[][] cells,           // [7][5], 0 = background, 1 = foreground
            OklchColor background,
            OklchColor foreground,
            String[] words,
            Style style) {}

    /** 14×20 pixel grid (3×3 block per cell, on-cells fill a 2×2 corner). */
    public record PixelGrid(
            int width, int height,
            byte[] pixels,           // row-major, 0 = bg, 1 = fg
            OklchColor background,
            OklchColor foreground,
            Style style) {}

    // =========================================================================
    // Tunable constants
    // =========================================================================

    private static final double BASE_L_MIN  = 0.6;
    private static final double BASE_L_MAX  = 0.8;
    private static final double CHROMA_MIN  = 0.10;
    private static final double CHROMA_MAX  = 0.30;

    private static final double HC_L_ADD       = 0.20;   // HC foreground +L
    private static final double BG_L_SPREAD    = 0.50;   // bg = fg − 0.5
    private static final double BG_L_EXTRA_HC  = 0.30;   // additional bg − 0.3 in HC/mono
    private static final double CHROMA_STD_ADD = 0.00;
    private static final double CHROMA_HC_ADD  = 0.10;

    // =========================================================================
    // CRC-8/SMBUS (poly 0x07, init 0x00, no reflect, no xorout)
    // =========================================================================

    private static int crc8(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x07) & 0xFF
                                          :  (crc << 1)        & 0xFF;
            }
        }
        return crc;
    }

    /** Compute the four c-bytes from a 32-byte hash. */
    private static int[] cBytes(byte[] hb) {
        int[] c = new int[4];
        byte[] buf = new byte[8];
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 8; k++) buf[k] = hb[i + k * 4];
            c[i] = crc8(buf);
        }
        return c;
    }

    // =========================================================================
    // OKLCH → sRGB
    // =========================================================================

    private static double srgbEncode(double v) {
        v = Math.max(0.0, Math.min(1.0, v));
        if (v <= 0.0031308) return 12.92 * v;
        return 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055;
    }

    private static OklchColor makeColor(double L, double C, double h) {
        double hr = h * Math.PI / 180.0;
        double a  = C * Math.cos(hr);
        double b  = C * Math.sin(hr);

        double l_ = L + 0.3963377774 * a + 0.2158037573 * b;
        double m_ = L - 0.1055613458 * a - 0.0638541728 * b;
        double s_ = L - 0.0894841775 * a - 1.2914855480 * b;

        double l3 = l_ * l_ * l_;
        double m3 = m_ * m_ * m_;
        double s3 = s_ * s_ * s_;

        double r  = srgbEncode( 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3);
        double g  = srgbEncode(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3);
        double bv = srgbEncode(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3);

        int ri = Math.max(0, Math.min(255, (int) Math.round(r  * 255)));
        int gi = Math.max(0, Math.min(255, (int) Math.round(g  * 255)));
        int bi = Math.max(0, Math.min(255, (int) Math.round(bv * 255)));
        return new OklchColor(L, C, h, String.format("#%02x%02x%02x", ri, gi, bi));
    }

    // =========================================================================
    // Cell + colour derivation
    // =========================================================================

    private static int[][] buildCells(int[] c) {
        // 21 bits MSB-first, row-major into a 7×3 base grid.
        // Bit i = (c[1] | c[2] | c[3]) bit (20-i) from a 21-bit big-endian stream.
        int stream = (c[1] << 13) | (c[2] << 5) | (c[3] >>> 3);   // 21-bit
        int[][] base = new int[7][3];
        for (int idx = 0; idx < 21; idx++) {
            int bit = (stream >>> (20 - idx)) & 1;
            base[idx / 3][idx % 3] = bit;
        }
        // Mirror around the centre column (col 2 is axis):
        //   out cols: [0]=base[0], [1]=base[1], [2]=base[2], [3]=base[1], [4]=base[0]
        int[][] out = new int[7][5];
        for (int r = 0; r < 7; r++) {
            out[r][0] = base[r][0];
            out[r][1] = base[r][1];
            out[r][2] = base[r][2];
            out[r][3] = base[r][1];
            out[r][4] = base[r][0];
        }
        return out;
    }

    private static OklchColor[] deriveColors(int[] c, Style style) {
        int hi4 = (c[0] >>> 4) & 0xF;
        int lo4 = c[0] & 0xF;
        double hue       = hi4 * (360.0 / 16.0);
        double chromaOff = CHROMA_MIN + lo4 * ((CHROMA_MAX - CHROMA_MIN) / 15.0);

        boolean flip   = ((c[3] >>> 2) & 1) == 1;
        int     lumIdx = c[3] & 0x3;
        double  baseL  = BASE_L_MIN + lumIdx * ((BASE_L_MAX - BASE_L_MIN) / 3.0);

        // Foreground / background luminance and chroma per style
        double fgL, bgL, fgC, bgC;
        switch (style) {
            case STANDARD -> {
                fgL = baseL;
                bgL = fgL - BG_L_SPREAD;
                fgC = chromaOff + CHROMA_STD_ADD;
                bgC = chromaOff + CHROMA_STD_ADD;
            }
            case HIGH_CONTRAST -> {
                fgL = baseL + HC_L_ADD;
                bgL = fgL - BG_L_SPREAD - BG_L_EXTRA_HC;
                fgC = chromaOff + CHROMA_HC_ADD;
                bgC = chromaOff + CHROMA_HC_ADD;
            }
            case MONOCHROME -> {
                fgL = baseL + HC_L_ADD;
                bgL = fgL - BG_L_SPREAD - BG_L_EXTRA_HC;
                fgC = 0.0;
                bgC = 0.0;
            }
            default -> throw new IllegalStateException();
        }

        // Clamp into a sensible OKLCH range
        fgL = clamp01(fgL);
        bgL = clamp01(bgL);

        if (flip) {
            double t = fgL; fgL = bgL; bgL = t;
        }

        double fgH = hue;
        double bgH = (hue + 180.0) % 360.0;

        OklchColor fg = makeColor(fgL, fgC, fgH);
        OklchColor bg = makeColor(bgL, bgC, bgH);
        return new OklchColor[]{ bg, fg };
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    // =========================================================================
    // BIP-39 verbal companion (same scheme as Hallmark: last 33 bits → 3 words)
    // =========================================================================

    private static String[] deriveWords(byte[] hb) {
        long hi = hb[27] & 0x7FL;
        long lo = ((hb[28] & 0xFFL) << 24)
                | ((hb[29] & 0xFFL) << 16)
                | ((hb[30] & 0xFFL) <<  8)
                |  (hb[31] & 0xFFL);
        int i1 = (int) (((hi << 5) | (lo >> 27)) & 0x7FFL);
        int i2 = (int) ((lo >> 11) & 0x7FFL);
        int i3 = (int) (lo & 0x7FFL);
        String[] w = Bip39English.WORDS;
        return new String[]{w[i1], w[i2], w[i3]};
    }

    // =========================================================================
    // 14×20 pixel grid (same block layout as Hallmark, no accent variant)
    // =========================================================================

    private static byte[] genPixels(int[][] cells) {
        byte[] px = new byte[280];
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 5; x++) {
                if (cells[y][x] == 0) continue;
                int bx = x * 3;
                int by = y * 3;
                px[ by      * 14 + bx     ] = 1;
                px[ by      * 14 + bx + 1 ] = 1;
                px[(by + 1) * 14 + bx     ] = 1;
                px[(by + 1) * 14 + bx + 1] = 1;
            }
        }
        return px;
    }

    // =========================================================================
    // SHA-256
    // =========================================================================

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public static VisSpec spec(String input, Style style) {
        byte[] hb = sha256(input);
        int[]  c  = cBytes(hb);
        int[][] cells = buildCells(c);
        OklchColor[] cols = deriveColors(c, style);
        String[] words = deriveWords(hb);
        return new VisSpec(cells, cols[0], cols[1], words, style);
    }

    public static VisSpec spec(String input) {
        return spec(input, Style.STANDARD);
    }

    public static String[] words(String input) {
        return deriveWords(sha256(input));
    }

    public static PixelGrid pixels(String input, Style style) {
        byte[] hb = sha256(input);
        int[]  c  = cBytes(hb);
        int[][] cells = buildCells(c);
        OklchColor[] cols = deriveColors(c, style);
        return new PixelGrid(14, 20, genPixels(cells), cols[0], cols[1], style);
    }
}
