package com.onlineexam.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/** Top bar with the screen title, the signed-in user and a logout button. */
final class HeaderBar extends JPanel {
    private static final long serialVersionUID = 1L;

    HeaderBar(String title, String userText, Runnable onLogout) {
        super(new BorderLayout());
        setBackground(Theme.PRIMARY);
        setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        JLabel t = new JLabel(title);
        t.setFont(Theme.H2);
        t.setForeground(java.awt.Color.WHITE);
        add(t, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        JLabel user = new JLabel(userText);
        user.setForeground(java.awt.Color.WHITE);
        right.add(user);
        JButton logout = Theme.button("Log out", Theme.Kind.SECONDARY);
        logout.addActionListener(e -> onLogout.run());
        right.add(logout);
        add(right, BorderLayout.EAST);
    }
}
