package com.onlineexam.ui;

import com.onlineexam.controller.AuthController;
import com.onlineexam.model.Role;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.event.ActionListener;

/** Role-aware login screen. */
class LoginPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final MainFrame frame;
    private final AuthController auth;
    private final JRadioButton studentRadio = new JRadioButton("Student", true);
    private final JRadioButton adminRadio = new JRadioButton("Administrator");
    private final JLabel idLabel = Theme.bold("Email");
    private final JTextField idField = Theme.field(24);
    private final JPasswordField passwordField = Theme.passwordField(24);
    private final JLabel message = new JLabel(" ");
    private final JLabel dbStatus = new JLabel(" ");
    private final JButton loginButton = Theme.button("Log in", Theme.Kind.PRIMARY);

    LoginPanel(MainFrame frame, AuthController auth) {
        this.frame = frame;
        this.auth = auth;
        setBackground(Theme.BG);
        setLayout(new GridBagLayout());

        JPanel card = Theme.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setPreferredSize(new Dimension(540, 520));

        JLabel title = Theme.title("Online Examination System");
        title.setAlignmentX(LEFT_ALIGNMENT);
        JLabel sub = Theme.muted("Sign in to continue");
        sub.setAlignmentX(LEFT_ALIGNMENT);
        card.add(title);
        card.add(sub);
        card.add(spacer(16));

        ButtonGroup group = new ButtonGroup();
        group.add(studentRadio);
        group.add(adminRadio);
        studentRadio.setOpaque(false);
        adminRadio.setOpaque(false);
        JPanel roles = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        roles.setOpaque(false);
        roles.add(studentRadio);
        roles.add(adminRadio);
        roles.setAlignmentX(LEFT_ALIGNMENT);
        roles.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        card.add(roles);
        card.add(spacer(12));

        idLabel.setAlignmentX(LEFT_ALIGNMENT);
        card.add(idLabel);
        idField.setAlignmentX(LEFT_ALIGNMENT);
        idField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        card.add(idField);
        card.add(spacer(10));
        JLabel pwLabel = Theme.bold("Password");
        pwLabel.setAlignmentX(LEFT_ALIGNMENT);
        card.add(pwLabel);
        passwordField.setAlignmentX(LEFT_ALIGNMENT);
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        card.add(passwordField);
        card.add(spacer(8));

        message.setForeground(Theme.DANGER);
        message.setAlignmentX(LEFT_ALIGNMENT);
        card.add(message);
        card.add(spacer(6));

        loginButton.setAlignmentX(LEFT_ALIGNMENT);
        card.add(loginButton);
        card.add(spacer(10));

        JButton register = Theme.button("Create a student account", Theme.Kind.SECONDARY);
        register.setAlignmentX(LEFT_ALIGNMENT);
        card.add(register);
        card.add(spacer(6));
        JButton registerAdmin = Theme.button("Create an administrator account", Theme.Kind.SECONDARY);
        registerAdmin.setAlignmentX(LEFT_ALIGNMENT);
        registerAdmin.setVisible(false);
        card.add(registerAdmin);
        card.add(spacer(14));
        dbStatus.setAlignmentX(LEFT_ALIGNMENT);
        card.add(dbStatus);
        add(card);

        ActionListener roleChanged = e -> {
            idLabel.setText(adminRadio.isSelected() ? "Username" : "Email");
            register.setVisible(studentRadio.isSelected());
            registerAdmin.setVisible(adminRadio.isSelected());
            message.setText(" ");
        };
        studentRadio.addActionListener(roleChanged);
        adminRadio.addActionListener(roleChanged);
        ActionListener doLogin = e -> login();
        loginButton.addActionListener(doLogin);
        idField.addActionListener(doLogin);
        passwordField.addActionListener(doLogin);
        register.addActionListener(e -> frame.showRegister());
        registerAdmin.addActionListener(e -> frame.showRegisterAdmin());

        auth.checkDatabase(problem -> {
            if (problem == null) {
                dbStatus.setForeground(Theme.SUCCESS);
                dbStatus.setText("Database connection OK");
            } else {
                dbStatus.setForeground(Theme.DANGER);
                dbStatus.setText(Theme.html(problem));
            }
        });
    }

    private static JPanel spacer(int h) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(1, h));
        p.setPreferredSize(new Dimension(1, h));
        return p;
    }

    private void login() {
        if (!loginButton.isEnabled()) return;
        message.setText(" ");
        Role role = adminRadio.isSelected() ? Role.ADMIN : Role.STUDENT;
        loginButton.setEnabled(false);
        loginButton.setText("Signing in...");
        auth.login(role, idField.getText(), new String(passwordField.getPassword()), session -> {
            passwordField.setText("");
            frame.showDashboard(session);
        }, ex -> {
            loginButton.setEnabled(true);
            loginButton.setText("Log in");
            passwordField.setText("");
            message.setText(Theme.html(Theme.messageOf(ex)));
        });
    }
}
