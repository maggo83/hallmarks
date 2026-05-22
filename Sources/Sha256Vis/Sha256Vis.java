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

    private static final double BASE_L_MIN  = 0.5;
    private static final double BASE_L_MAX  = 0.7;
    private static final double CHROMA_MIN  = 0.05;
    private static final double CHROMA_MAX  = 0.25;

    private static final double HC_L_ADD       = 0.30;   // HC foreground +L
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

    /**
     * Bit-index lookup for the point-mirror layout (20-bit stream → 7×5 grid).
     *
     * <pre>
     *  0  1  2  3  4
     *  5  6  7  8  9
     * 10 11 12 13 17
     * 14 15 16 15 14
     * 17 18 12 11 10
     * 19  8  7  6  5
     *  4  3  2  1  0
     * </pre>
     *
     * Cells that share the same index are 180° point-symmetric around [3][2].
     * The four unpaired extras (9, 13, 18, 19) use the remaining bits.
     */
    private static final int[] POINT_MAP = {
         0,  1,  2,  3,  4,
         5,  6,  7,  8,  9,
        10, 11, 12, 13, 17,
        14, 15, 16, 15, 14,
        17, 18, 12, 11, 10,
        19,  8,  7,  6,  5,
         4,  3,  2,  1,  0,
    };

    /**
     * Build the 7×5 cell grid.
     *
     * <p>Normal path (axis mirror selected by SHA parity):<br>
     * Even: vertical mirror — rows 4,5,6 = rows 2,1,0; center cell forced off.<br>
     * Odd:  horizontal mirror — cols 3,4 = cols 1,0; center cell forced on.
     *
     * <p>Point-mirror override: if {@code swap=true} and the set-cell count
     * after cell-inversion is below 10, the grid is rebuilt using
     * {@link #POINT_MAP} (180° rotational symmetry around the center).
     *
     * <p>Cell inversion ({@code flip}) is always applied after mirroring.
     */
    private static int[][] buildCells(int[] c, boolean flip, boolean parityOdd, boolean swap) {
        int stream = (c[1] << 12) | (c[2] << 4) | (c[3] >>> 4);   // 20-bit

        int[][] grid = buildAxisGrid(stream, parityOdd);
        if (flip) invertCells(grid);

        // Count set bits in the raw 20-bit stream (before mirroring) to decide
        // whether to switch to point mirror.
        int rawSet = flip ? (20 - Integer.bitCount(stream & 0xFFFFF))
                          :        Integer.bitCount(stream & 0xFFFFF);
        if (swap && rawSet < 10) {
            grid = buildPointGrid(stream);
            if (flip) invertCells(grid);
        }
        return grid;
    }

    private static int[][] buildAxisGrid(int stream, boolean parityOdd) {
        int[][] grid = new int[7][5];
        if (!parityOdd) {
            // ── Even parity: vertical mirror (top↔bottom) ────────────────
            int bit = 0;
            for (int r = 0; r < 4; r++)
                for (int col = 0; col < 5; col++)
                    grid[r][col] = (stream >>> (19 - bit++)) & 1;
            grid[3][2] = 0;
            for (int col = 0; col < 5; col++) {
                grid[4][col] = grid[2][col];
                grid[5][col] = grid[1][col];
                grid[6][col] = grid[0][col];
            }
        } else {
            // ── Odd parity: horizontal mirror (left↔right) ───────────────
            int bit = 0;
            for (int col = 0; col < 3; col++)
                for (int r = 0; r < 7; r++) {
                    if (r == 3 && col == 2) continue;
                    grid[r][col] = (stream >>> (19 - bit++)) & 1;
                }
            grid[3][2] = 1;
            for (int r = 0; r < 7; r++) {
                grid[r][3] = grid[r][1];
                grid[r][4] = grid[r][0];
            }
        }
        return grid;
    }

    private static int[][] buildPointGrid(int stream) {
        int[][] grid = new int[7][5];
        for (int i = 0; i < 35; i++)
            grid[i / 5][i % 5] = (stream >>> (19 - POINT_MAP[i])) & 1;
        return grid;
    }

    private static void invertCells(int[][] grid) {
        for (int r = 0; r < 7; r++)
            for (int col = 0; col < 5; col++)
                grid[r][col] ^= 1;
    }

    private static int countSet(int[][] grid) {
        int n = 0;
        for (int[] row : grid) for (int v : row) n += v;
        return n;
    }

    /** @param swapLuminance when true, swap fg and bg luminance (driven by SHA-256 parity) */
    private static OklchColor[] deriveColors(int[] c, Style style, boolean swapLuminance) {
        int hi4 = (c[0] >>> 4) & 0xF;
        int lo4 = c[0] & 0xF;
        double hue       = hi4 * (360.0 / 16.0);
        double chromaOff = CHROMA_MIN + lo4 * ((CHROMA_MAX - CHROMA_MIN) / 15.0);

        int    lumIdx = (c[3] >>> 2) & 0x3;   // c[3] bits 3..2
        double baseL  = BASE_L_MIN + lumIdx * ((BASE_L_MAX - BASE_L_MIN) / 3.0);

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

        // SHA-256 parity swaps fg/bg luminance
        if (swapLuminance) {
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

    /** XOR-parity of all 32 SHA-256 bytes: true when the total number of set bits is odd. */
    private static boolean shaParity(byte[] hb) {
        int p = 0;
        for (byte b : hb) p ^= Integer.bitCount(b & 0xFF);
        return (p & 1) == 1;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public static VisSpec spec(String input, Style style) {
        byte[] hb        = sha256(input);
        int[]  c         = cBytes(hb);
        boolean parityOdd = shaParity(hb);           // selects mirror axis
        boolean flip      = ((c[3] >>> 1) & 1) == 1; // c[3] bit 1: invert cells
        boolean swap      = (c[3] & 1) == 1;         // c[3] bit 0: swap fg/bg luminance
        int[][] cells = buildCells(c, flip, parityOdd, swap);
        OklchColor[] cols = deriveColors(c, style, swap);
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
        byte[] hb        = sha256(input);
        int[]  c         = cBytes(hb);
        boolean parityOdd = shaParity(hb);
        boolean flip      = ((c[3] >>> 1) & 1) == 1;
        boolean swap      = (c[3] & 1) == 1;
        int[][] cells = buildCells(c, flip, parityOdd, swap);
        OklchColor[] cols = deriveColors(c, style, swap);
        return new PixelGrid(14, 20, genPixels(cells), cols[0], cols[1], style);
    }
}
