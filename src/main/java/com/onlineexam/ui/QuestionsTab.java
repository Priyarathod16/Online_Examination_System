package com.onlineexam.ui;

import com.onlineexam.controller.AdminController;
import com.onlineexam.model.Exam;
import com.onlineexam.model.Question;
import com.onlineexam.util.SearchUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/** Question management for one exam at a time. */
class QuestionsTab extends JPanel implements Refreshable {
    private static final long serialVersionUID = 1L;

    private final AdminController controller;
    private final JComboBox<Exam> examCombo = new JComboBox<Exam>();
    private final DefaultTableModel model = Theme.readOnlyModel("ID", "Question", "A", "B", "C", "D", "Correct", "Marks");
    private final JTable table = new JTable(model);
    private final JTextField search = Theme.field(18);
    private final JLabel info = Theme.muted(" ");
    private List<Question> all = new ArrayList<Question>();
    private List<Question> shown = new ArrayList<Question>();
    private List<Exam> exams = new ArrayList<Exam>();
    private boolean loading;
    private int pendingExamId = -1;

    QuestionsTab(AdminController controller) {
        super(new BorderLayout(0, 10));
        this.controller = controller;
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        top.setOpaque(false);
        top.add(Theme.bold("Exam:"));
        examCombo.setPreferredSize(new java.awt.Dimension(280, 32));
        top.add(examCombo);
        top.add(Theme.bold("Search:"));
        top.add(search);
        JButton add = Theme.button("Add question", Theme.Kind.PRIMARY);
        JButton edit = Theme.button("Edit", Theme.Kind.SECONDARY);
        JButton delete = Theme.button("Delete", Theme.Kind.DANGER);
        JButton reload = Theme.button("Refresh", Theme.Kind.SECONDARY);
        for (JButton b : new JButton[]{add, edit, delete, reload}) top.add(b);
        add(top, BorderLayout.NORTH);

        Theme.styleTable(table);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(1).setPreferredWidth(320);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(info, BorderLayout.SOUTH);

        examCombo.addActionListener(e -> {
            if (!loading) loadQuestions();
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        add.addActionListener(e -> {
            Exam ex = (Exam) examCombo.getSelectedItem();
            if (ex == null) {
                Theme.warn(this, "Create an exam first.");
                return;
            }
            new QuestionFormDialog(SwingUtilities.getWindowAncestor(this), controller, ex.getExamId(), null, this::loadQuestions).setVisible(true);
        });
        edit.addActionListener(e -> {
            Question q = selected();
            Exam ex = (Exam) examCombo.getSelectedItem();
            if (q != null && ex != null) {
                new QuestionFormDialog(SwingUtilities.getWindowAncestor(this), controller, ex.getExamId(), q, this::loadQuestions).setVisible(true);
            }
        });
        delete.addActionListener(e -> {
            Question q = selected();
            if (q != null && Theme.confirm(this, "Delete question " + q.getQuestionId() + "?", "Delete question")) {
                controller.deleteQuestion(q.getQuestionId(), this::loadQuestions, ex -> Theme.error(this, ex));
            }
        });
        reload.addActionListener(e -> refresh());
    }

    /** Called by the exams tab ("Manage questions"). */
    void selectExam(int examId) {
        pendingExamId = examId;   // consumed by refresh(), which runs when the tab becomes visible
    }

    private Question selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= shown.size()) {
            Theme.warn(this, "Please select a question first.");
            return null;
        }
        return shown.get(row);
    }

    @Override
    public void refresh() {
        Exam prev = (Exam) examCombo.getSelectedItem();
        final int wanted = pendingExamId >= 0 ? pendingExamId : (prev == null ? -1 : prev.getExamId());
        pendingExamId = -1;
        controller.loadExams(list -> {
            exams = list;
            loading = true;
            examCombo.removeAllItems();
            for (Exam e : list) examCombo.addItem(e);
            int idx = SearchUtil.binarySearchById(list, wanted, Exam::getExamId);
            if (idx >= 0) examCombo.setSelectedIndex(idx);
            loading = false;
            loadQuestions();
        }, ex -> Theme.error(this, ex));
    }

    private void loadQuestions() {
        Exam ex = (Exam) examCombo.getSelectedItem();
        model.setRowCount(0);
        all = new ArrayList<Question>();
        shown = new ArrayList<Question>();
        if (ex == null) {
            info.setText("No exams yet.");
            return;
        }
        info.setText("Loading...");
        final Exam exam = ex;
        controller.loadQuestions(exam.getExamId(), list -> {
            all = list;
            applyFilter();
            info.setText(list.size() + " question(s) in the bank; each attempt uses " + exam.getTotalQuestions()
                    + (list.size() < exam.getTotalQuestions() ? "  -  ADD MORE QUESTIONS (bank is too small)" : ""));
        }, e -> {
            info.setText(" ");
            Theme.error(this, e);
        });
    }

    private void applyFilter() {
        shown = SearchUtil.filter(all, search.getText(), q -> q.getText() + " " + String.join(" ", q.getOptions()));
        model.setRowCount(0);
        for (Question q : shown) {
            model.addRow(new Object[]{q.getQuestionId(), q.getText(), q.getOptions()[0], q.getOptions()[1], q.getOptions()[2],
                    q.getOptions()[3], String.valueOf(q.getCorrectOption()), Theme.num(q.getMarks())});
        }
    }
}
