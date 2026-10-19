package com.onlineexam.ui;

import com.onlineexam.exception.ServiceException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared colours, fonts and small component factories so every screen looks consistent. */
public final class Theme {
    private static final Logger LOG = Logger.getLogger(Theme.class.getName());

    public static final Color PRIMARY = new Color(0x2F5BEA);
    public static final Color BG = new Color(0xF4F6FB);
    public static final Color CARD = Color.WHITE;
    public static final Color TEXT = new Color(0x1F2937);
    public static final Color MUTED = new Color(0x6B7280);
    public static final Color SUCCESS = new Color(0x16A34A);
    public static final Color DANGER = new Color(0xDC2626);
    public static final Color WARN = new Color(0xD97706);
    public static final Color BORDER = new Color(0xD1D5DB);
    public static final Color SUCCESS_BG = new Color(0xDCFCE7);
    public static final Color DANGER_BG = new Color(0xFEE2E2);

    public static final Font BASE = new Font("SansSerif", Font.PLAIN, 14);
    public static final Font BOLD = new Font("SansSerif", Font.BOLD, 14);
    public static final Font H1 = new Font("SansSerif", Font.BOLD, 26);
    public static final Font H2 = new Font("SansSerif", Font.BOLD, 18);
    public static final Font QUESTION = new Font("SansSerif", Font.PLAIN, 18);

    public enum Kind { PRIMARY, SECONDARY, DANGER, SUCCESS }

    private Theme() { }

    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not set look and feel", e);
        }
        String[] keys = {"Label.font", "Button.font", "TextField.font", "PasswordField.font", "TextArea.font", "Table.font",
                "ComboBox.font", "CheckBox.font", "RadioButton.font", "TabbedPane.font", "OptionPane.messageFont",
                "OptionPane.buttonFont", "ToolTip.font"};
        for (String k : keys) UIManager.put(k, BASE);
        UIManager.put("TableHeader.font", BOLD);
        UIManager.put("Button.focus", new Color(0, 0, 0, 0));
    }

    // ----------------------------------------------------------- components

    /** A rounded, flat button that paints the same on every look and feel. */
    public static final class ModernButton extends JButton {
        private static final long serialVersionUID = 1L;
        private Color fill;
        private boolean hover;

        ModernButton(String text, Color fill) {
            super(text);
            this.fill = fill;
            setForeground(Color.WHITE);
            setFont(BOLD);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }

                @Override
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        public void setFill(Color c) {
            fill = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = fill;
            if (!isEnabled()) c = new Color(0xE5E7EB);
            else if (getModel().isPressed()) c = fill.darker();
            else if (hover) c = new Color(Math.min(255, fill.getRed() + 18), Math.min(255, fill.getGreen() + 18), Math.min(255, fill.getBlue() + 18));
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static ModernButton button(String text, Kind kind) {
        Color c;
        switch (kind) {
            case DANGER: c = DANGER; break;
            case SUCCESS: c = SUCCESS; break;
            case SECONDARY: c = new Color(0x4B5563); break;
            default: c = PRIMARY; break;
        }
        return new ModernButton(text, c);
    }

    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(TEXT);
        return l;
    }

    public static JLabel bold(String text) {
        JLabel l = label(text);
        l.setFont(BOLD);
        return l;
    }

    public static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(H1);
        l.setForeground(TEXT);
        return l;
    }

    public static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(H2);
        l.setForeground(TEXT);
        return l;
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        return l;
    }

    public static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }

    public static JTextField field(int columns) {
        JTextField f = new JTextField(columns);
        f.setBorder(fieldBorder());
        return f;
    }

    public static JPasswordField passwordField(int columns) {
        JPasswordField f = new JPasswordField(columns);
        f.setBorder(fieldBorder());
        return f;
    }

    public static JPanel card() {
        JPanel p = new JPanel();
        p.setBackground(CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(20, 24, 20, 24)));
        return p;
    }

    public static JPanel page() {
        JPanel p = new JPanel();
        p.setBackground(BG);
        return p;
    }

    public static void styleTable(JTable t) {
        t.setRowHeight(28);
        t.setGridColor(new Color(0xE5E7EB));
        t.setSelectionBackground(new Color(0xDBE5FF));
        t.setSelectionForeground(TEXT);
        t.setFillsViewportHeight(true);
        t.setShowVerticalLines(false);
        JTableHeader h = t.getTableHeader();
        h.setBackground(new Color(0xE8ECF8));
        h.setForeground(TEXT);
        h.setReorderingAllowed(false);
    }

    public static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    // -------------------------------------------------------------- dialogs

    /** Converts an exception into a message that is safe to show. Unexpected errors are logged, not displayed. */
    public static String messageOf(Exception e) {
        if (e instanceof ServiceException) return e.getMessage();
        LOG.log(Level.SEVERE, "Unexpected error", e);
        return "Something unexpected went wrong. Please try again.";
    }

    public static String html(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return "<html><body style='width:380px'>" + safe + "</body></html>";
    }

    public static void error(Component parent, Exception e) {
        JOptionPane.showMessageDialog(parent, html(messageOf(e)), "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static void warn(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, html(message), "Please check", JOptionPane.WARNING_MESSAGE);
    }

    public static void info(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, html(message), "Information", JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component parent, String message, String title) {
        return JOptionPane.showConfirmDialog(parent, html(message), title, JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    public static String num(BigDecimal v) {
        return v == null ? "-" : v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
