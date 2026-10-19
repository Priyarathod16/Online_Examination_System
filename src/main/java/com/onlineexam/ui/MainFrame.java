package com.onlineexam.ui;

import com.onlineexam.controller.AuthController;
import com.onlineexam.controller.ExamController;
import com.onlineexam.model.Exam;
import com.onlineexam.model.ExamAttempt;
import com.onlineexam.model.Result;
import com.onlineexam.service.UserSession;

import javax.swing.JComponent;
import javax.swing.JFrame;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** The single application window; screens are swapped in and out of its content area. */
public class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;

    private final AuthController authController = new AuthController();
    private JComponent current;

    public MainFrame() {
        super("Online Examination System");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        setSize(1180, 780);
        setMinimumSize(new java.awt.Dimension(960, 640));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { onCloseRequested(); }
        });
        showLogin();
    }

    private void onCloseRequested() {
        if (current instanceof ExamPanel) {
            boolean leave = Theme.confirm(this, "Your exam is still running. Closing the window does NOT stop the timer; "
                    + "you can resume until the deadline, after which it is submitted automatically.\n\nClose the application anyway?",
                    "Exam in progress");
            if (!leave) return;
        }
        dispose();
        System.exit(0);
    }

    private void show(JComponent screen) {
        if (current != null) getContentPane().remove(current);
        current = screen;
        getContentPane().add(screen, BorderLayout.CENTER);
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    public void showLogin() { show(new LoginPanel(this, authController)); }

    public void showRegister() { show(new RegisterPanel(this, authController)); }

    public void showRegisterAdmin() { show(new AdminRegisterPanel(this, authController)); }

    public void showDashboard(UserSession session) {
        if (session.isAdmin()) show(new AdminDashboardPanel(this, session));
        else show(new StudentDashboardPanel(this, session));
    }

    public void showInstructions(UserSession session, Exam exam) { show(new InstructionsPanel(this, session, exam)); }

    public void showExam(UserSession session, ExamController controller, ExamAttempt attempt) {
        show(new ExamPanel(this, session, controller, attempt));
    }

    public void showResult(UserSession session, Result result) { show(new ResultPanel(this, session, result)); }
}
