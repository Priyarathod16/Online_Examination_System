package com.onlineexam.ui;

import com.onlineexam.controller.AdminController;
import com.onlineexam.exception.ValidationException;
import com.onlineexam.model.Exam;
import com.onlineexam.util.DateUtil;
import com.onlineexam.util.ValidationUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.math.BigDecimal;

/** Create / edit exam dialog. Validation messages stay in the dialog; it closes only after a confirmed save. */
class ExamFormDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final AdminController controller;
    private final Exam existing;
    private final Runnable onSaved;

    private final JTextField title = Theme.field(30);
    private final JTextField description = Theme.field(30);
    private final JTextArea instructions = new JTextArea(5, 30);
    private final JTextField duration = Theme.field(8);
    private final JTextField totalQuestions = Theme.field(8);
    private final JTextField passing = Theme.field(8);
    private final JTextField negative = Theme.field(8);
    private final JTextField maxAttempts = Theme.field(8);
    private final JTextField maxConcurrent = Theme.field(8);
    private final JTextField start = Theme.field(16);
    private final JTextField end = Theme.field(16);
    private final JCheckBox active = new JCheckBox("Active (visible to students)");
    private final JCheckBox shuffleQuestions = new JCheckBox("Shuffle question order and pick a random subset", true);
    private final JCheckBox shuffleOptions = new JCheckBox("Shuffle answer options", true);
    private final JCheckBox allowReview = new JCheckBox("Allow answer review after submission");
    private final JLabel message = new JLabel(" ");
    private final JButton save = Theme.button("Save exam", Theme.Kind.PRIMARY);

    ExamFormDialog(Window owner, AdminController controller, Exam existing, Runnable onSaved) {
        super(owner, existing == null ? "New exam" : "Edit exam", ModalityType.APPLICATION_MODAL);
        this.controller = controller;
        this.existing = existing;
        this.onSaved = onSaved;

        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setBorder(Theme.fieldBorder());
        FormBuilder form = new FormBuilder();
        form.add("Title *", title).add("Description", description)
                .add("Instructions", new JScrollPane(instructions))
                .add("Duration (minutes) *", duration).add("Questions per attempt *", totalQuestions)
                .add("Passing percentage *", passing).add("Negative marks per wrong answer", negative)
                .add("Maximum attempts *", maxAttempts).add("Max. students at once (blank = unlimited)", maxConcurrent)
                .add("Available from (" + DateUtil.INPUT_PATTERN + ")", start)
                .add("Available until (" + DateUtil.INPUT_PATTERN + ")", end)
                .addWide(active).addWide(shuffleQuestions).addWide(shuffleOptions).addWide(allowReview);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        content.add(new JScrollPane(form.build()), BorderLayout.CENTER);
        message.setForeground(Theme.DANGER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(message, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = Theme.button("Cancel", Theme.Kind.SECONDARY);
        buttons.add(cancel);
        buttons.add(save);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);

        populate();
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> save());
        setPreferredSize(new Dimension(620, 720));
        pack();
        setLocationRelativeTo(owner);
    }

    private void populate() {
        if (existing == null) {
            duration.setText("30");
            totalQuestions.setText("10");
            passing.setText("40");
            negative.setText("0");
            maxAttempts.setText("1");
            return;
        }
        title.setText(existing.getTitle());
        description.setText(existing.getDescription() == null ? "" : existing.getDescription());
        instructions.setText(existing.getInstructions() == null ? "" : existing.getInstructions());
        duration.setText(String.valueOf(existing.getDurationMinutes()));
        totalQuestions.setText(String.valueOf(existing.getTotalQuestions()));
        passing.setText(Theme.num(existing.getPassingPercentage()));
        negative.setText(Theme.num(existing.getNegativeMarks()));
        maxAttempts.setText(String.valueOf(existing.getMaxAttempts()));
        maxConcurrent.setText(existing.getMaxConcurrent() == null ? "" : String.valueOf(existing.getMaxConcurrent()));
        start.setText(DateUtil.input(existing.getStartTime()));
        end.setText(DateUtil.input(existing.getEndTime()));
        active.setSelected(existing.isActive());
        shuffleQuestions.setSelected(existing.isShuffleQuestions());
        shuffleOptions.setSelected(existing.isShuffleOptions());
        allowReview.setSelected(existing.isAllowReview());
    }

    private Exam readForm() throws ValidationException {
        Exam e = new Exam();
        e.setExamId(existing == null ? 0 : existing.getExamId());
        e.setTitle(title.getText());
        e.setDescription(description.getText());
        e.setInstructions(instructions.getText());
        e.setDurationMinutes(ValidationUtil.parseInt(duration.getText(), "Duration"));
        e.setTotalQuestions(ValidationUtil.parseInt(totalQuestions.getText(), "Questions per attempt"));
        e.setPassingPercentage(ValidationUtil.parseDecimal(passing.getText(), "Passing percentage"));
        String neg = negative.getText().trim();
        e.setNegativeMarks(neg.isEmpty() ? BigDecimal.ZERO : ValidationUtil.parseDecimal(neg, "Negative marks"));
        e.setMaxAttempts(ValidationUtil.parseInt(maxAttempts.getText(), "Maximum attempts"));
        String mc = maxConcurrent.getText().trim();
        e.setMaxConcurrent(mc.isEmpty() ? null : Integer.valueOf(ValidationUtil.parseInt(mc, "Max. students at once")));
        e.setStartTime(DateUtil.parseOptional(start.getText(), "Available from"));
        e.setEndTime(DateUtil.parseOptional(end.getText(), "Available until"));
        e.setActive(active.isSelected());
        e.setShuffleQuestions(shuffleQuestions.isSelected());
        e.setShuffleOptions(shuffleOptions.isSelected());
        e.setAllowReview(allowReview.isSelected());
        return e;
    }

    private void save() {
        message.setText(" ");
        final Exam exam;
        try {
            exam = readForm();
        } catch (ValidationException ex) {
            message.setText(Theme.html(ex.getMessage()));
            return;
        }
        save.setEnabled(false);
        controller.saveExam(exam, saved -> {
            dispose();
            SwingUtilities.invokeLater(onSaved);
        }, ex -> {
            save.setEnabled(true);
            message.setText(Theme.html(Theme.messageOf(ex)));
        });
    }
}
