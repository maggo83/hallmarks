// Sha256Vis — interactive Swing demo.
// SPDX-License-Identifier: MIT
//
// Run from the repo root:
//   javac -d out Sources/Sha256Vis/*.java && java -cp out sha256vis.DemoApp

package sha256vis;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

public class DemoApp {

    // ── UI palette ────────────────────────────────────────────────────────────
    private static final Color APP_BG    = new Color(0xF2, 0xF2, 0xF7);
    private static final Color CARD_BG   = Color.WHITE;
    private static final Color TEXT_FG   = new Color(0x1C, 0x1C, 0x1E);
    private static final Color MUTED_FG  = new Color(0x8E, 0x8E, 0x93);
    private static final Color BORDER_FG = new Color(0xD1, 0xD1, 0xD6);

    private static JLabel wordsLabel;
    private static final List<VisView>   allViews   = new ArrayList<>();
    private static final List<PixelView> pixelViews = new ArrayList<>();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Sha256Vis");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(APP_BG);
            root.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

            JScrollPane scroll = new JScrollPane(buildContent());
            scroll.setBorder(null);
            scroll.setBackground(APP_BG);
            scroll.getViewport().setBackground(APP_BG);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            root.add(scroll, BorderLayout.CENTER);

            frame.setContentPane(root);
            frame.setPreferredSize(new Dimension(820, 780));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static JPanel buildContent() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(APP_BG);

        // Input
        JPanel inputCard = makeCard(14);
        inputCard.setLayout(new BoxLayout(inputCard, BoxLayout.Y_AXIS));
        inputCard.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel inputLabel = makeCaptionLabel("INPUT");
        inputLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        inputCard.add(inputLabel);
        inputCard.add(vgap(5));

        JTextField field = new JTextField("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq");
        field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_FG),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        int fieldH = field.getPreferredSize().height * 2;
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, fieldH));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldH));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        inputCard.add(field);
        inputCard.add(vgap(8));

        wordsLabel = new JLabel(" ");
        wordsLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        wordsLabel.setForeground(MUTED_FG);
        wordsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        inputCard.add(wordsLabel);

        content.add(inputCard);
        content.add(vgap(20));

        // Styles
        content.add(sectionLabel("Styles"));
        content.add(vgap(8));
        JPanel stylesRow = hrow(
            variantCard(Sha256Vis.Style.STANDARD,      200, "Standard"),
            variantCard(Sha256Vis.Style.HIGH_CONTRAST, 200, "High Contrast"),
            variantCard(Sha256Vis.Style.MONOCHROME,    200, "Monochrome"));
        content.add(stylesRow);
        content.add(vgap(20));

        // Sizes (Standard)
        content.add(sectionLabel("Sizes  (Standard)"));
        content.add(vgap(8));
        JPanel sizesCard = makeCard(12);
        sizesCard.setLayout(new BoxLayout(sizesCard, BoxLayout.X_AXIS));
        sizesCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        boolean first = true;
        for (int sz : new int[]{160, 120, 80, 56, 40, 28}) {
            if (!first) sizesCard.add(hgap(14));
            first = false;
            JPanel sub = vstack(CARD_BG);
            VisView v = new VisView(Sha256Vis.Style.STANDARD, sz);
            allViews.add(v);
            v.setAlignmentX(Component.CENTER_ALIGNMENT);
            sub.add(v);
            sub.add(vgap(4));
            JLabel lbl = makeCaptionLabel(sz + " px");
            lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            sub.add(lbl);
            sizesCard.add(sub);
        }
        sizesCard.add(Box.createHorizontalGlue());
        content.add(sizesCard);
        content.add(vgap(20));

        // Pixel grid ×6
        content.add(sectionLabel("Pixel Grid  (14 \u00d7 20,  \u00d76 zoom)"));
        content.add(vgap(8));
        content.add(pixelRow(6));
        content.add(vgap(20));

        // Pixel grid ×1
        content.add(sectionLabel("Pixel Grid  (14 \u00d7 20,  actual size)"));
        content.add(vgap(8));
        content.add(pixelRow(1));
        content.add(vgap(24));

        // Live update
        updateAll(field.getText());
        Timer debounce = new Timer(80, e -> updateAll(field.getText()));
        debounce.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            void kick() { debounce.restart(); }
            public void insertUpdate(DocumentEvent e)  { kick(); }
            public void removeUpdate(DocumentEvent e)  { kick(); }
            public void changedUpdate(DocumentEvent e) { kick(); }
        });

        return content;
    }

    private static JPanel pixelRow(int cellPx) {
        JPanel card = makeCard(12);
        card.setLayout(new BoxLayout(card, BoxLayout.X_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        boolean first = true;
        for (Sha256Vis.Style style : Sha256Vis.Style.values()) {
            if (!first) card.add(hgap(20));
            first = false;
            JPanel sub = vstack(CARD_BG);
            PixelView pv = new PixelView(style, cellPx);
            pixelViews.add(pv);
            pv.setAlignmentX(Component.CENTER_ALIGNMENT);
            sub.add(pv);
            sub.add(vgap(4));
            JLabel lbl = makeCaptionLabel(styleName(style));
            lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            sub.add(lbl);
            card.add(sub);
        }
        card.add(Box.createHorizontalGlue());
        return card;
    }

    private static void updateAll(String input) {
        String[] w = Sha256Vis.words(input);
        wordsLabel.setText(w[0] + "  \u00b7  " + w[1] + "  \u00b7  " + w[2]);
        allViews.forEach(v -> v.setInput(input));
        pixelViews.forEach(v -> v.setInput(input));
    }

    // =========================================================================
    // Views
    // =========================================================================

    static class VisView extends JComponent {
        private final Sha256Vis.Style style;
        private Sha256Vis.VisSpec spec;

        VisView(Sha256Vis.Style style, int w) {
            this.style = style;
            int h = (int) Math.round(w * 132.0 / 100.0);
            Dimension d = new Dimension(w, h);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        void setInput(String input) {
            spec = Sha256Vis.spec(input, style);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (spec == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                paintVis(g2, spec, getWidth(), getHeight());
            } finally {
                g2.dispose();
            }
        }
    }

    static class PixelView extends JComponent {
        private final Sha256Vis.Style style;
        private final int cellPx;
        private Sha256Vis.PixelGrid grid;

        PixelView(Sha256Vis.Style style, int cellPx) {
            this.style = style;
            this.cellPx = cellPx;
            Dimension d = new Dimension(14 * cellPx, 20 * cellPx);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        void setInput(String input) {
            grid = Sha256Vis.pixels(input, style);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (grid == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            try {
                byte[] px = grid.pixels();
                Color bg = parseHex(grid.background().hex());
                Color fg = parseHex(grid.foreground().hex());
                for (int row = 0; row < 20; row++) {
                    for (int col = 0; col < 14; col++) {
                        int v = px[row * 14 + col] & 0xFF;
                        g2.setColor(v == 0 ? bg : fg);
                        g2.fillRect(col * cellPx, row * cellPx, cellPx, cellPx);
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }

    // =========================================================================
    // Paint logic — 100×132 viewBox, 7×5 dots in a rounded-rect tile.
    // =========================================================================

    static void paintVis(Graphics2D g2, Sha256Vis.VisSpec spec, int width, int height) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double scale = Math.min(width / 100.0, height / 132.0);
        double w  = 100 * scale, h = 132 * scale;
        double ox = (width  - w) / 2.0, oy = (height - h) / 2.0;

        double padding      = 10.0 * scale;
        double cell         = 16.0 * scale;
        double cornerRadius = 16.0 * scale;
        double dotR         = cell * 0.40;

        // Background tile
        g2.setColor(parseHex(spec.background().hex()));
        g2.fill(new RoundRectangle2D.Double(ox, oy, w, h,
                cornerRadius * 2, cornerRadius * 2));

        // Cells
        g2.setColor(parseHex(spec.foreground().hex()));
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 5; x++) {
                if (spec.cells()[y][x] == 0) continue;
                double cx = ox + padding + x * cell + cell / 2.0;
                double cy = oy + padding + y * cell + cell / 2.0;
                g2.fill(new Ellipse2D.Double(cx - dotR, cy - dotR, dotR * 2, dotR * 2));
            }
        }
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private static JPanel variantCard(Sha256Vis.Style style, int size, String label) {
        JPanel card = makeCard(12);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        VisView v = new VisView(style, size);
        allViews.add(v);
        v.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(v);
        card.add(vgap(6));
        JLabel lbl = makeCaptionLabel(label);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lbl);
        return card;
    }

    private static JPanel hrow(JPanel a, JPanel b, JPanel c) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBackground(APP_BG);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(a);
        row.add(hgap(12));
        row.add(b);
        row.add(hgap(12));
        row.add(c);
        Dimension pref = row.getPreferredSize();
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        return row;
    }

    static Color parseHex(String hex) {
        return new Color(
            Integer.parseInt(hex.substring(1, 3), 16),
            Integer.parseInt(hex.substring(3, 5), 16),
            Integer.parseInt(hex.substring(5, 7), 16));
    }

    private static JPanel makeCard(int pad) {
        JPanel p = new JPanel();
        p.setBackground(CARD_BG);
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_FG),
            BorderFactory.createEmptyBorder(pad, pad, pad, pad)));
        return p;
    }

    private static JPanel vstack(Color bg) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(bg);
        return p;
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        l.setForeground(TEXT_FG);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel makeCaptionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        l.setForeground(MUTED_FG);
        return l;
    }

    private static Component vgap(int h) { return Box.createVerticalStrut(h); }
    private static Component hgap(int w) { return Box.createHorizontalStrut(w); }

    private static String styleName(Sha256Vis.Style style) {
        return switch (style) {
            case STANDARD      -> "Standard";
            case HIGH_CONTRAST -> "High Contrast";
            case MONOCHROME    -> "Monochrome";
        };
    }
}
