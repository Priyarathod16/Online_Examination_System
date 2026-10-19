package com.onlineexam.ui;

import com.onlineexam.controller.ExamController;
import com.onlineexam.exception.ExamClosedException;
import com.onlineexam.model.AttemptQuestion;
import com.onlineexam.model.ExamAttempt;
import com.onlineexam.service.UserSession;
import com.onlineexam.util.DateUtil;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The examination screen. The countdown shown here is only a display: the official deadline is stored in the
 * database and checked on every save and on submit. Answers are keyed by question id (HashMap), so shuffling never
 * changes which question an answer belongs to. A status line only says "saved" after the database confirmed it.
 */
class ExamPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final MainFrame frame;
    private final UserSession session;
    private final ExamController controller;
    private final ExamAttempt attempt;
    private final List<AttemptQuestion> questions;
    private final Map<Integer, Character> answers;      // question id -> ORIGINAL option letter
    private final Set<Integer> marked;                   // question ids flagged for review
    private final Set<Integer> unsaved = new HashSet<Integer>();   // answers not yet confirmed by the database
    private final long deadlineNanos;
    private final Timer timer;

    private int index;
    private boolean updating;
    private boolean submitting;
    private boolean warnedFiveMinutes;

    private final JLabel timerLabel = new JLabel("--:--");
    private final JLabel progressLabel = Theme.bold(" ");
    private final JLabel statusLabel = Theme.muted(" ");
    private final JLabel questionHeader = Theme.bold(" ");
    private final JTextArea questionArea = new WrapArea();
    private final JRadioButton[] radios = new JRadioButton[4];
    private final ButtonGroup group = new ButtonGroup();
    private final JCheckBox markBox = new JCheckBox("Mark for review");
    private final Theme.ModernButton prev = Theme.button("Previous", Theme.Kind.SECONDARY);
    private final Theme.ModernButton next = Theme.button("Next", Theme.Kind.PRIMARY);
    private final Theme.ModernButton clear = Theme.button("Clear answer", Theme.Kind.SECONDARY);
    private final Theme.ModernButton submit = Theme.button("Submit exam", Theme.Kind.SUCCESS);
    private final Theme.ModernButton exit = Theme.button("Save & exit", Theme.Kind.SECONDARY);
    private final List<Theme.ModernButton> palette = new ArrayList<Theme.ModernButton>();

    ExamPanel(MainFrame frame, UserSession session, ExamController controller, ExamAttempt attempt) {
        super(new BorderLayout());
        this.frame = frame;
        this.session = session;
        this.controller = controller;
        this.attempt = attempt;
        this.questions = attempt.getQuestions();
        this.answers = attempt.getAnswers();
        this.marked = attempt.getMarked();
        this.deadlineNanos = System.nanoTime() + attempt.getRemainingSeconds() * 1000000000L;
        setBackground(Theme.BG);
        buildUi();
        showQuestion(0);
        this.timer = new Timer(250, e -> tick());
        timer.start();
        tick();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Theme.PRIMARY);
        top.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        JLabel title = new JLabel(attempt.getExamTitle() + "  (attempt " + attempt.getAttemptNo() + ")");
        title.setFont(Theme.H2);
        title.setForeground(Color.WHITE);
        top.add(title, BorderLayout.WEST);
        timerLabel.setFont(new java.awt.Font("Monospaced", java.awt.Font.BOLD, 28));
        timerLabel.setForeground(Color.WHITE);
        top.add(timerLabel, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // question card
        JPanel card = Theme.card();
        card.setLayout(new BorderLayout(0, 12));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(questionHeader, BorderLayout.WEST);
        head.add(progressLabel, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        questionArea.setEditable(false);
        questionArea.setLineWrap(true);
        questionArea.setWrapStyleWord(true);
        questionArea.setFont(Theme.QUESTION);
        questionArea.setForeground(Theme.TEXT);
        questionArea.setOpaque(false);
        JPanel body = new ScrollablePanel(new BorderLayout(0, 16));
        body.setOpaque(false);
        body.add(questionArea, BorderLayout.NORTH);
        JPanel opts = new JPanel(new GridLayout(4, 1, 0, 8));
        opts.setOpaque(false);
        for (int i = 0; i < radios.length; i++) {
            final int position = i;
            radios[i] = new JRadioButton();
            radios[i].setOpaque(false);
            radios[i].setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 16));
            radios[i].setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            group.add(radios[i]);
            radios[i].addActionListener(e -> onOptionChosen(position));
            opts.add(radios[i]);
        }
        JPanel optsWrap = new JPanel(new BorderLayout());
        optsWrap.setOpaque(false);
        optsWrap.add(opts, BorderLayout.NORTH);
        body.add(optsWrap, BorderLayout.CENTER);
        card.add(transparentScroll(body), BorderLayout.CENTER);

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        nav.setOpaque(false);
        markBox.setOpaque(false);
        nav.add(prev);
        nav.add(next);
        nav.add(clear);
        nav.add(markBox);
        card.add(nav, BorderLayout.SOUTH);

        JPanel centre = new JPanel(new BorderLayout());
        centre.setOpaque(false);
        centre.setBorder(BorderFactory.createEmptyBorder(14, 14, 6, 8));
        centre.add(card, BorderLayout.CENTER);
        add(centre, BorderLayout.CENTER);

        // right side: palette and submit
        JPanel side = Theme.card();
        side.setLayout(new BorderLayout(0, 10));
        side.setPreferredSize(new Dimension(270, 100));
        side.add(Theme.heading("Questions"), BorderLayout.NORTH);
        JPanel grid = new JPanel(new GridLayout(0, 5, 5, 5));
        grid.setOpaque(false);
        for (int i = 0; i < questions.size(); i++) {
            final int target = i;
            Theme.ModernButton b = Theme.button(String.valueOf(i + 1), Theme.Kind.SECONDARY);
            b.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            b.addActionListener(e -> showQuestion(target));
            palette.add(b);
            grid.add(b);
        }
        JPanel gridWrap = new JPanel(new BorderLayout());
        gridWrap.setOpaque(false);
        gridWrap.add(grid, BorderLayout.NORTH);
        side.add(transparentScroll(gridWrap), BorderLayout.CENTER);
        JPanel legend = new JPanel(new GridLayout(0, 1, 0, 2));
        legend.setOpaque(false);
        legend.add(legendItem(Theme.SUCCESS, "Answered"));
        legend.add(legendItem(Theme.WARN, "Marked for review"));
        legend.add(legendItem(new Color(0x4B5563), "Not answered"));
        JPanel sideBottom = new JPanel(new BorderLayout(0, 8));
        sideBottom.setOpaque(false);
        sideBottom.add(legend, BorderLayout.NORTH);
        JPanel btns = new JPanel(new GridLayout(2, 1, 0, 8));
        btns.setOpaque(false);
        btns.add(submit);
        btns.add(exit);
        sideBottom.add(btns, BorderLayout.SOUTH);
        side.add(sideBottom, BorderLayout.SOUTH);
        JPanel sideWrap = new JPanel(new BorderLayout());
        sideWrap.setOpaque(false);
        sideWrap.setBorder(BorderFactory.createEmptyBorder(14, 6, 6, 14));
        sideWrap.add(side, BorderLayout.CENTER);
        add(sideWrap, BorderLayout.EAST);

        JPanel status = new JPanel(new BorderLayout());
        status.setOpaque(false);
        status.setBorder(BorderFactory.createEmptyBorder(0, 20, 8, 20));
        status.add(statusLabel, BorderLayout.WEST);
        add(status, BorderLayout.SOUTH);

        prev.addActionListener(e -> showQuestion(index - 1));
        next.addActionListener(e -> showQuestion(index + 1));
        clear.addActionListener(e -> clearAnswer());
        markBox.addActionListener(e -> toggleMark());
        submit.addActionListener(e -> submitExam(false));
        exit.addActionListener(e -> saveAndExit());
    }

    private static JLabel legendItem(Color c, String text) {
        JLabel l = new JLabel("\u25A0 " + text);
        l.setForeground(c);
        return l;
    }

    private static JScrollPane transparentScroll(JPanel view) {
        JScrollPane sp = new JScrollPane(view, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** A text area whose preferred height follows its parent's width, so wrapped text is never clipped. */
    private static final class WrapArea extends JTextArea {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredSize() {
            java.awt.Container parent = getParent();
            int width = parent == null ? 0 : parent.getWidth();
            if (width > 0) {
                setSize(width, Short.MAX_VALUE);
                return new Dimension(width, super.getPreferredSize().height);
            }
            return super.getPreferredSize();
        }
    }

    /** Panel that always matches the scroll pane's width, so only vertical scrolling is ever needed. */
    private static final class ScrollablePanel extends JPanel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;

        ScrollablePanel(java.awt.LayoutManager layout) { super(layout); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return 64; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ------------------------------------------------------ question view

    private void showQuestion(int i) {
        if (i < 0 || i >= questions.size()) return;
        index = i;
        AttemptQuestion q = questions.get(i);
        updating = true;
        questionHeader.setText("Question " + (i + 1) + " of " + questions.size() + "   (" + Theme.num(q.getMarks()) + " mark"
                + (q.getMarks().compareTo(java.math.BigDecimal.ONE) == 0 ? "" : "s") + ")");
        questionArea.setText(q.getText());
        questionArea.setCaretPosition(0);
        Character selected = answers.get(q.getQuestionId());
        group.clearSelection();
        for (int k = 0; k < radios.length; k++) {
            radios[k].setText((char) ('A' + k) + ".  " + q.getOptionText(k));
            if (selected != null && selected.charValue() == q.getOptionLetter(k)) radios[k].setSelected(true);
        }
        markBox.setSelected(marked.contains(q.getQuestionId()));
        updating = false;
        prev.setEnabled(i > 0 && !submitting);
        next.setEnabled(i < questions.size() - 1 && !submitting);
        refreshPalette();
    }

    private void refreshPalette() {
        int answered = 0;
        for (int i = 0; i < questions.size(); i++) {
            int id = questions.get(i).getQuestionId();
            boolean has = answers.containsKey(id);
            if (has) answered++;
            Theme.ModernButton b = palette.get(i);
            b.setFill(marked.contains(id) ? Theme.WARN : has ? Theme.SUCCESS : new Color(0x4B5563));
            b.setText(i == index ? "[" + (i + 1) + "]" : String.valueOf(i + 1));
        }
        progressLabel.setText("Answered " + answered + " of " + questions.size());
    }

    // ------------------------------------------------------------ actions

    private void onOptionChosen(int position) {
        if (updating || submitting) return;
        AttemptQuestion q = questions.get(index);
        Character letter = Character.valueOf(q.getOptionLetter(position));
        answers.put(q.getQuestionId(), letter);
        unsaved.add(q.getQuestionId());
        refreshPalette();
        sendSave(q.getQuestionId(), letter);
    }

    private void clearAnswer() {
        if (submitting) return;
        AttemptQuestion q = questions.get(index);
        if (!answers.containsKey(q.getQuestionId())) return;
        answers.remove(q.getQuestionId());
        unsaved.remove(q.getQuestionId());
        updating = true;
        group.clearSelection();
        updating = false;
        refreshPalette();
        sendSave(q.getQuestionId(), null);
    }

    private void sendSave(final int questionId, final Character letter) {
        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setText("Saving...");
        controller.saveAnswer(attempt.getAttemptId(), questionId, letter, () -> {
            if (letter == null || letter.equals(answers.get(questionId))) unsaved.remove(questionId);
            statusLabel.setForeground(Theme.SUCCESS);
            statusLabel.setText("Answer saved");
        }, ex -> {
            if (ex instanceof ExamClosedException) {
                submitExam(true);      // the server says the deadline has passed
                return;
            }
            if (letter != null) unsaved.add(questionId);
            statusLabel.setForeground(Theme.DANGER);
            statusLabel.setText("NOT saved - " + Theme.messageOf(ex) + " It will be retried when you submit.");
        });
    }

    private void toggleMark() {
        if (updating || submitting) return;
        final int id = questions.get(index).getQuestionId();
        final boolean flag = markBox.isSelected();
        if (flag) marked.add(id); else marked.remove(id);
        refreshPalette();
        controller.setMarked(attempt.getAttemptId(), id, flag, () -> { }, ex -> {
            statusLabel.setForeground(Theme.DANGER);
            statusLabel.setText("The review mark was not saved on the server (it is kept on this screen only).");
        });
    }

    private Map<Integer, Character> pendingAnswers() {
        Map<Integer, Character> pending = new HashMap<Integer, Character>();
        for (Integer id : unsaved) {
            Character c = answers.get(id);
            if (c != null) pending.put(id, c);
        }
        return pending;
    }

    private void setControlsEnabled(boolean enabled) {
        prev.setEnabled(enabled && index > 0);
        next.setEnabled(enabled && index < questions.size() - 1);
        clear.setEnabled(enabled);
        submit.setEnabled(enabled);
        exit.setEnabled(enabled);
        markBox.setEnabled(enabled);
        for (JRadioButton r : radios) r.setEnabled(enabled);
        for (Theme.ModernButton b : palette) b.setEnabled(enabled);
    }

    private void saveAndExit() {
        if (submitting) return;
        if (!Theme.confirm(this, "Your answers are already saved on the server. The timer keeps running while you are away; "
                + "you can resume from the exam list before the deadline.\n\nLeave the exam now?", "Leave exam")) return;
        timer.stop();
        for (Integer id : new ArrayList<Integer>(unsaved)) {       // best-effort flush; the executor outlives this screen
            Character c = answers.get(id);
            if (c != null) controller.saveAnswer(attempt.getAttemptId(), id, c, () -> { }, e -> { });
        }
        controller.close();
        frame.showDashboard(session);
    }

    /** auto == true: time is up (or the server rejected a save as late) - no confirmation, always submits. */
    private void submitExam(boolean auto) {
        if (submitting) return;
        if (!auto) {
            int unanswered = questions.size() - answers.size();
            String msg = "Submit your exam now?" + (unanswered > 0 ? "\n\nYou have " + unanswered + " unanswered question(s)." : "")
                    + (marked.isEmpty() ? "" : "\nYou marked " + marked.size() + " question(s) for review.")
                    + "\n\nYou cannot change your answers after submitting.";
            if (!Theme.confirm(this, msg, "Submit exam")) return;
        }
        submitting = true;
        timer.stop();
        setControlsEnabled(false);
        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setText(auto ? "Time is up - submitting your exam..." : "Submitting your exam...");
        controller.submit(attempt.getAttemptId(), pendingAnswers(), result -> {
            controller.close();
            frame.showResult(session, result);
        }, ex -> {
            submitting = false;
            statusLabel.setForeground(Theme.DANGER);
            statusLabel.setText("Submission was NOT confirmed.");
            int choice = JOptionPane.showConfirmDialog(this, Theme.html(Theme.messageOf(ex)
                    + "\n\nYour answers saved so far are safe. Try submitting again?"), "Submission failed",
                    JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                submitExam(true);
            } else {
                controller.close();
                frame.showDashboard(session);
            }
        });
    }

    // --------------------------------------------------------------- timer

    private void tick() {
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1000000L;
        long seconds = Math.max(0, (remainingMillis + 999) / 1000);
        timerLabel.setText(DateUtil.formatSeconds(seconds));
        timerLabel.setForeground(seconds <= 60 ? new Color(0xFCA5A5) : Color.WHITE);
        if (seconds <= 300 && seconds > 0 && !warnedFiveMinutes) {
            warnedFiveMinutes = true;
            statusLabel.setForeground(Theme.WARN);
            statusLabel.setText("Less than 5 minutes remaining.");
        }
        if (remainingMillis <= 0 && !submitting) {
            timer.stop();
            submitExam(true);
        }
    }

    @Override
    public void removeNotify() {
        if (timer != null) timer.stop();
        super.removeNotify();
    }
}
