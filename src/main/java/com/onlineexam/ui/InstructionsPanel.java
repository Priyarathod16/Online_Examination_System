package com.onlineexam.ui;

import com.onlineexam.controller.ExamController;
import com.onlineexam.exception.ExamFullException;
import com.onlineexam.model.Exam;
import com.onlineexam.service.UserSession;
import com.onlineexam.util.DateUtil;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;

/** Shows the exam rules and starts (or resumes) the attempt once the student confirms. */
class InstructionsPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final MainFrame frame;
    private final UserSession session;
    private final Exam exam;
    private final ExamController controller;
    private final JButton startButton;
    private final JLabel message = new JLabel(" ");

    InstructionsPanel(MainFrame frame, UserSession session, Exam exam) {
        super(new GridBagLayout());
        this.frame = frame;
        this.session = session;
        this.exam = exam;
        this.controller = new ExamController(session);
        setBackground(Theme.BG);

        boolean resume = exam.getActiveAttemptId() != null;
        JPanel card = Theme.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setPreferredSize(new Dimension(720, 640));

        card.add(left(Theme.title(exam.getTitle())));
        if (exam.getDescription() != null) card.add(left(Theme.muted(exam.getDescription())));
        card.add(gap(10));
        card.add(left(Theme.bold("Duration: " + exam.getDurationMinutes() + " minutes     Questions: " + exam.getTotalQuestions()
                + "     Pass mark: " + Theme.num(exam.getPassingPercentage()) + "%")));
        card.add(left(Theme.label("Attempts used: " + exam.getAttemptsUsed() + " of " + exam.getMaxAttempts()
                + (exam.getEndTime() == null ? "" : "     Closes: " + DateUtil.display(exam.getEndTime())))));
        card.add(gap(10));
        card.add(left(Theme.heading("Instructions")));

        StringBuilder text = new StringBuilder();
        if (exam.getInstructions() != null) text.append(exam.getInstructions()).append("\n\n");
        text.append("General rules\n")
                .append("  - Each question has exactly one correct answer.\n")
                .append("  - Your answer is saved to the server as soon as you select it.\n")
                .append("  - Use Previous/Next or the question palette to move around, and 'Mark for review' to flag questions.\n")
                .append("  - The timer is enforced by the server using the deadline recorded when you start. ")
                .append("When it reaches zero your attempt is submitted automatically.\n")
                .append("  - If you leave or your computer closes, you can resume before the deadline; the timer keeps running.\n");
        if (exam.getNegativeMarks().signum() > 0) {
            text.append("  - Each wrong answer deducts ").append(Theme.num(exam.getNegativeMarks())).append(" marks. Unanswered questions score zero.\n");
        } else {
            text.append("  - There is no negative marking. Unanswered questions score zero.\n");
        }
        text.append(exam.isAllowReview() ? "  - You will be able to review your answers after submitting.\n"
                : "  - Correct answers are not shown after submission for this exam.\n");
        JTextArea area = new JTextArea(text.toString());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(Theme.BG);
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        card.add(scroll);
        card.add(gap(10));

        final JCheckBox agree = new JCheckBox("I have read and understood the instructions");
        agree.setOpaque(false);
        card.add(left(agree));
        card.add(gap(6));
        message.setForeground(Theme.DANGER);
        card.add(left(message));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        startButton = Theme.button(resume ? "Resume exam" : "Start exam", Theme.Kind.SUCCESS);
        startButton.setEnabled(false);
        JButton back = Theme.button("Back", Theme.Kind.SECONDARY);
        buttons.add(startButton);
        buttons.add(back);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        card.add(left(buttons));
        add(card);

        agree.addActionListener(e -> startButton.setEnabled(agree.isSelected()));
        back.addActionListener(e -> {
            controller.close();
            frame.showDashboard(session);
        });
        startButton.addActionListener(e -> start());
    }

    private void start() {
        message.setText(" ");
        startButton.setEnabled(false);
        controller.startOrResume(exam.getExamId(), attempt -> frame.showExam(session, controller, attempt), ex -> {
            startButton.setEnabled(true);
            if (ex instanceof ExamFullException) {
                offerWaitingList(ex.getMessage());
            } else {
                message.setText(Theme.html(Theme.messageOf(ex)));
            }
        });
    }

    private void offerWaitingList(String reason) {
        if (Theme.confirm(this, reason + "\n\nJoin (or stay in) the waiting list? You can come back and press Start when a seat is free.",
                "Exam is full")) {
            controller.joinWaitingList(exam.getExamId(),
                    pos -> Theme.info(this, "You are number " + pos + " in the waiting list. Return to this screen later and press Start."),
                    e -> Theme.error(this, e));
        }
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
