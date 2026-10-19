package com.onlineexam.ui;

import com.onlineexam.controller.AuthController;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.ActionListener;

/** Administrator account creation; needs the administrator registration key. */
class AdminRegisterPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final JTextField nameField = Theme.field(24);
    private final JPasswordField keyField = Theme.passwordField(24);
    private final JPasswordField passwordField = Theme.passwordField(24);
    private final JPasswordField confirmField = Theme.passwordField(24);
    private final JLabel message = new JLabel(" ");
    private final JButton submit = Theme.button("Create administrator account", Theme.Kind.PRIMARY);

    AdminRegisterPanel(MainFrame frame, AuthController auth) {
        setBackground(Theme.BG);
        setLayout(new GridBagLayout());
        JPanel card = Theme.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setPreferredSize(new Dimension(480, 640));

        card.add(left(Theme.title("Create administrator account")));
        card.add(left(Theme.muted("Password: at least 8 characters with a letter and a digit")));
        card.add(gap(12));
        for (Object[] row : new Object[][]{{"Username", nameField}, {"Password", passwordField}, {"Confirm password", confirmField}, {"Administrator registration key", keyField}}) {
            card.add(left(Theme.bold((String) row[0])));
            JTextField f = (JTextField) row[1];
            f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            card.add(left(f));
            card.add(gap(8));
        }
        message.setForeground(Theme.DANGER);
        card.add(left(message));
        card.add(gap(6));
        card.add(left(submit));
        card.add(gap(8));
        JButton back = Theme.button("Back to login", Theme.Kind.SECONDARY);
        card.add(left(back));
        add(card);

        back.addActionListener(e -> frame.showLogin());
        ActionListener doSubmit = e -> {
            if (!submit.isEnabled()) return;
            message.setText(" ");
            submit.setEnabled(false);
            submit.setText("Creating...");
            auth.registerAdmin(nameField.getText(), new String(passwordField.getPassword()),
                    new String(confirmField.getPassword()), new String(keyField.getPassword()), () -> {
                        Theme.info(this, "The administrator account was created. You can log in now.");
                        frame.showLogin();
                    }, ex -> {
                        submit.setEnabled(true);
                        submit.setText("Create administrator account");
                        message.setText(Theme.html(Theme.messageOf(ex)));
                    });
        };
        submit.addActionListener(doSubmit);
        confirmField.addActionListener(doSubmit);
        keyField.addActionListener(doSubmit);
    }

    private static <T extends javax.swing.JComponent> T left(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel gap(int h) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(1, h));
        p.setPreferredSize(new Dimension(1, h));
        return p;
    }
}
