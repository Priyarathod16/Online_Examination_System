package com.onlineexam.ui;

import com.onlineexam.controller.StudentController;
import com.onlineexam.model.Exam;
import com.onlineexam.model.HistoryEntry;
import com.onlineexam.service.UserSession;
import com.onlineexam.util.DateUtil;
import com.onlineexam.util.SearchUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/** Student home: available exams, result history, leaderboard. */
class StudentDashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final MainFrame frame;
    private final UserSession session;
    private final StudentController controller;

    private final DefaultTableModel examModel = Theme.readOnlyModel("Exam", "Duration", "Questions", "Attempts used", "Closes", "Status");
    private final JTable examTable = new JTable(examModel);
    private final JLabel examInfo = Theme.muted(" ");
    private List<Exam> exams = new ArrayList<Exam>();      // sorted by id (binary search is used to restore the selection)

    private final DefaultTableModel historyModel = Theme.readOnlyModel("Exam", "Attempt", "Score", "Percentage", "Result", "Finished", "Submitted");
    private final JTable historyTable = new JTable(historyModel);
    private final JLabel historyInfo = Theme.muted(" ");
    private List<HistoryEntry> history = new ArrayList<HistoryEntry>();

    StudentDashboardPanel(MainFrame frame, UserSession session) {
        super(new BorderLayout());
        this.frame = frame;
        this.session = session;
        this.controller = new StudentController(session);
        setBackground(Theme.BG);

        final JTabbedPane tabs = new JTabbedPane();
        final JPanel examsPanel = buildExamsPanel();
        final JPanel historyPanel = buildHistoryPanel();
        final LeaderboardPanel leaderboard = new LeaderboardPanel(
                (ok, err) -> controller.loadLeaderboardExams(ok, err),
                (examId, ok, err) -> controller.loadLeaderboard(examId, ok, err));
        tabs.addTab("Available exams", examsPanel);
        tabs.addTab("My results", historyPanel);
        tabs.addTab("Leaderboard", leaderboard);
        tabs.addChangeListener(e -> {
            java.awt.Component c = tabs.getSelectedComponent();
            if (c == examsPanel) loadExams();
            else if (c == historyPanel) loadHistory();
            else leaderboard.refresh();
        });

        add(new HeaderBar("Student dashboard", "Welcome, " + session.getDisplayName(), () -> frame.showLogin()), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        loadExams();
    }

    // ----------------------------------------------------------- exams tab

    private JPanel buildExamsPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        top.setOpaque(false);
        JButton start = Theme.button("Start / resume selected exam", Theme.Kind.PRIMARY);
        JButton reload = Theme.button("Refresh", Theme.Kind.SECONDARY);
        top.add(start);
        top.add(reload);
        p.add(top, BorderLayout.NORTH);
        Theme.styleTable(examTable);
        examTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        p.add(new JScrollPane(examTable), BorderLayout.CENTER);
        p.add(examInfo, BorderLayout.SOUTH);
        start.addActionListener(e -> openSelectedExam());
        reload.addActionListener(e -> loadExams());
        return p;
    }

    private void loadExams() {
        final int keep = examTable.getSelectedRow() >= 0 && examTable.getSelectedRow() < exams.size()
                ? exams.get(examTable.getSelectedRow()).getExamId() : -1;
        examInfo.setText("Loading...");
        controller.loadAvailableExams(list -> {
            exams = list;
            examModel.setRowCount(0);
            for (Exam e : list) {
                boolean resume = e.getActiveAttemptId() != null;
                boolean usedUp = !resume && e.getAttemptsUsed() >= e.getMaxAttempts();
                examModel.addRow(new Object[]{e.getTitle(), e.getDurationMinutes() + " min", e.getTotalQuestions(),
                        e.getAttemptsUsed() + " / " + e.getMaxAttempts(), e.getEndTime() == null ? "No deadline" : DateUtil.display(e.getEndTime()),
                        resume ? "In progress - resume" : usedUp ? "No attempts left" : "Available"});
            }
            examInfo.setText(list.isEmpty() ? "No exams are open right now." : list.size() + " exam(s) available");
            int idx = keep < 0 ? -1 : SearchUtil.binarySearchById(list, keep, Exam::getExamId);
            if (idx >= 0) examTable.setRowSelectionInterval(idx, idx);
        }, ex -> {
            examInfo.setText(" ");
            Theme.error(this, ex);
        });
    }

    private void openSelectedExam() {
        int row = examTable.getSelectedRow();
        if (row < 0 || row >= exams.size()) {
            Theme.warn(this, "Please select an exam first.");
            return;
        }
        frame.showInstructions(session, exams.get(row));
    }

    // --------------------------------------------------------- history tab

    private JPanel buildHistoryPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        top.setOpaque(false);
        JButton view = Theme.button("View result", Theme.Kind.PRIMARY);
        JButton review = Theme.button("Review answers", Theme.Kind.SUCCESS);
        JButton reload = Theme.button("Refresh", Theme.Kind.SECONDARY);
        top.add(view);
        top.add(review);
        top.add(reload);
        p.add(top, BorderLayout.NORTH);
        Theme.styleTable(historyTable);
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        p.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        p.add(historyInfo, BorderLayout.SOUTH);
        view.addActionListener(e -> {
            HistoryEntry h = selectedHistory();
            if (h != null) controller.loadResult(h.getAttemptId(), r -> frame.showResult(session, r), ex -> Theme.error(this, ex));
        });
        review.addActionListener(e -> {
            HistoryEntry h = selectedHistory();
            if (h == null) return;
            if (!h.isReviewAllowed()) {
                Theme.info(this, "Answer review is not enabled for this exam.");
                return;
            }
            controller.loadReview(h.getAttemptId(), items -> new ReviewDialog(javax.swing.SwingUtilities.getWindowAncestor(this),
                    h.getExamTitle(), items).setVisible(true), ex -> Theme.error(this, ex));
        });
        reload.addActionListener(e -> loadHistory());
        return p;
    }

    private HistoryEntry selectedHistory() {
        int row = historyTable.getSelectedRow();
        if (row < 0 || row >= history.size()) {
            Theme.warn(this, "Please select a result first.");
            return null;
        }
        return history.get(row);
    }

    private void loadHistory() {
        historyInfo.setText("Loading...");
        controller.loadHistory(list -> {
            history = list;
            historyModel.setRowCount(0);
            for (HistoryEntry h : list) {
                historyModel.addRow(new Object[]{h.getExamTitle(), "#" + h.getAttemptNo(), Theme.num(h.getScore()) + " / " + Theme.num(h.getMaxScore()),
                        Theme.num(h.getPercentage()) + "%", h.getStatus(),
                        h.getAttemptStatus() == com.onlineexam.model.AttemptStatus.TIMED_OUT ? "Time expired" : "Submitted",
                        DateUtil.display(h.getSubmittedAt())});
            }
            historyInfo.setText(list.isEmpty() ? "You have not completed any exams yet." : list.size() + " attempt(s)");
        }, ex -> {
            historyInfo.setText(" ");
            Theme.error(this, ex);
        });
    }
}
