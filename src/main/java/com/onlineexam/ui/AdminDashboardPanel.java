package com.onlineexam.ui;

import com.onlineexam.controller.AdminController;
import com.onlineexam.service.UserSession;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;

/** Administrator home: exams, questions, attempts/results, students and leaderboard. */
class AdminDashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    AdminDashboardPanel(MainFrame frame, UserSession session) {
        super(new BorderLayout());
        setBackground(Theme.BG);
        final AdminController controller = new AdminController(session);

        final JTabbedPane tabs = new JTabbedPane();
        final QuestionsTab questionsTab = new QuestionsTab(controller);
        final ExamsTab examsTab = new ExamsTab(controller, exam -> {
            questionsTab.selectExam(exam.getExamId());
            tabs.setSelectedComponent(questionsTab);
        });
        final AttemptsTab attemptsTab = new AttemptsTab(controller);
        final StudentsTab studentsTab = new StudentsTab(controller);
        final LeaderboardPanel leaderboard = new LeaderboardPanel(
                (ok, err) -> controller.loadExams(ok, err),
                (examId, ok, err) -> controller.loadLeaderboard(examId, ok, err));

        tabs.addTab("Exams", examsTab);
        tabs.addTab("Questions", questionsTab);
        tabs.addTab("Attempts & results", attemptsTab);
        tabs.addTab("Leaderboard", leaderboard);
        tabs.addTab("Students", studentsTab);
        tabs.addChangeListener(e -> {
            java.awt.Component c = tabs.getSelectedComponent();
            if (c instanceof Refreshable) ((Refreshable) c).refresh();
        });

        add(new HeaderBar("Administrator dashboard", "Signed in as " + session.getDisplayName(), () -> frame.showLogin()), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        examsTab.refresh();
    }
}
