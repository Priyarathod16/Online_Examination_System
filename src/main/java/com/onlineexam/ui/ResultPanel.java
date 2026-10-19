package com.onlineexam.ui;

import com.onlineexam.controller.StudentController;
import com.onlineexam.model.AttemptStatus;
import com.onlineexam.model.Exam;
import com.onlineexam.model.Result;
import com.onlineexam.model.ResultStatus;
import com.onlineexam.service.UserSession;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;

/** Score, percentage and pass/fail. Review is offered only if the exam allows it. */
class ResultPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    ResultPanel(MainFrame frame, UserSession session, Result result) {
        super(new GridBagLayout());
        setBackground(Theme.BG);
        final StudentController controller = new StudentController(session);

        JPanel card = Theme.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setPreferredSize(new Dimension(700, 470));
        card.add(left(Theme.muted("Exam result")));
        card.add(left(Theme.title(result.getExamTitle())));
        card.add(gap(14));

        boolean pass = result.getStatus() == ResultStatus.PASS;
        JLabel status = new JLabel(pass ? "PASSED" : "FAILED");
        status.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 34));
        status.setForeground(pass ? Theme.SUCCESS : Theme.DANGER);
        card.add(left(status));
        card.add(gap(10));
        JLabel score = new JLabel("Score: " + Theme.num(result.getScore()) + " / " + Theme.num(result.getMaxScore()));
        score.setFont(Theme.H2);
        card.add(left(score));
        JLabel pct = new JLabel("Percentage: " + Theme.num(result.getPercentage()) + "%");
        pct.setFont(Theme.H2);
        card.add(left(pct));
        card.add(gap(10));
        if (result.getAttemptStatus() == AttemptStatus.TIMED_OUT) {
            card.add(left(Theme.muted("The time limit was reached, so the exam was submitted automatically.")));
        }
        card.add(left(Theme.muted(result.isReviewAllowed() ? "You can review your answers below."
                : "Answer review is not available for this exam.")));
        card.add(gap(18));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton dashboard = Theme.button("Back to dashboard", Theme.Kind.PRIMARY);
        JButton board = Theme.button("Leaderboard", Theme.Kind.SECONDARY);
        buttons.add(dashboard);
        buttons.add(board);
        if (result.isReviewAllowed()) {
            JButton review = Theme.button("Review answers", Theme.Kind.SUCCESS);
            buttons.add(review);
            review.addActionListener(e -> controller.loadReview(result.getAttemptId(),
                    items -> new ReviewDialog(SwingUtilities.getWindowAncestor(this), result.getExamTitle(), items).setVisible(true),
                    ex -> Theme.error(this, ex)));
        }
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        card.add(left(buttons));
        add(card);

        dashboard.addActionListener(e -> frame.showDashboard(session));
        board.addActionListener(e -> {
            final Exam only = new Exam();
            only.setExamId(result.getExamId());
            only.setTitle(result.getExamTitle());
            LeaderboardPanel panel = new LeaderboardPanel((ok, err) -> ok.accept(java.util.Collections.singletonList(only)),
                    (examId, ok, err) -> controller.loadLeaderboard(examId, ok, err));
            JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Leaderboard", JDialog.ModalityType.APPLICATION_MODAL);
            d.setLayout(new BorderLayout());
            d.add(panel, BorderLayout.CENTER);
            d.setSize(760, 480);
            d.setLocationRelativeTo(this);
            panel.refresh();
            d.setVisible(true);
        });
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
