package com.onlineexam.ui;

import com.onlineexam.controller.AdminController;
import com.onlineexam.exception.ValidationException;
import com.onlineexam.model.Question;
import com.onlineexam.util.ValidationUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;

/** Add / edit a multiple-choice question. */
class QuestionFormDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final AdminController controller;
    private final int examId;
    private final Question existing;
    private final Runnable onSaved;

    private final JTextArea text = new JTextArea(4, 40);
    private final JTextField[] options = {Theme.field(40), Theme.field(40), Theme.field(40), Theme.field(40)};
    private final JComboBox<String> correct = new JComboBox<String>(new String[]{"A", "B", "C", "D"});
    private final JTextField marks = Theme.field(8);
    private final JLabel message = new JLabel(" ");
    private final JButton save = Theme.button("Save question", Theme.Kind.PRIMARY);

    QuestionFormDialog(Window owner, AdminController controller, int examId, Question existing, Runnable onSaved) {
        super(owner, existing == null ? "Add question" : "Edit question", ModalityType.APPLICATION_MODAL);
        this.controller = controller;
        this.examId = examId;
        this.existing = existing;
        this.onSaved = onSaved;

        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(Theme.fieldBorder());
        FormBuilder form = new FormBuilder();
        form.add("Question *", new JScrollPane(text));
        for (int i = 0; i < 4; i++) form.add("Option " + (char) ('A' + i) + " *", options[i]);
        form.add("Correct option *", correct).add("Marks *", marks);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        content.add(form.build(), BorderLayout.CENTER);
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

        if (existing == null) {
            marks.setText("1");
        } else {
            text.setText(existing.getText());
            for (int i = 0; i < 4; i++) options[i].setText(existing.getOptions()[i]);
            correct.setSelectedItem(String.valueOf(existing.getCorrectOption()));
            marks.setText(Theme.num(existing.getMarks()));
        }
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> save());
        pack();
        setLocationRelativeTo(owner);
    }

    private void save() {
        message.setText(" ");
        final Question q = new Question();
        try {
            q.setQuestionId(existing == null ? 0 : existing.getQuestionId());
            q.setExamId(examId);
            q.setText(text.getText());
            String[] opts = new String[4];
            for (int i = 0; i < 4; i++) opts[i] = options[i].getText();
            q.setOptions(opts);
            q.setCorrectOption(((String) correct.getSelectedItem()).charAt(0));
            q.setMarks(ValidationUtil.parseDecimal(marks.getText(), "Marks"));
        } catch (ValidationException ex) {
            message.setText(Theme.html(ex.getMessage()));
            return;
        }
        save.setEnabled(false);
        controller.saveQuestion(q, saved -> {
            dispose();
            SwingUtilities.invokeLater(onSaved);
        }, ex -> {
            save.setEnabled(true);
            message.setText(Theme.html(Theme.messageOf(ex)));
        });
    }
}
