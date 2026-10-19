package com.onlineexam.ui;

import com.onlineexam.controller.AdminController;
import com.onlineexam.model.Exam;
import com.onlineexam.util.DateUtil;
import com.onlineexam.util.SearchUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
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
import java.util.function.Consumer;

/** Exam management: create, edit, delete, activate, search. */
class ExamsTab extends JPanel implements Refreshable {
    private static final long serialVersionUID = 1L;

    private final AdminController controller;
    private final Consumer<Exam> openQuestions;
    private final DefaultTableModel model = Theme.readOnlyModel("ID", "Title", "Duration", "Questions (bank / per attempt)",
            "Pass %", "Neg. marks", "Attempts", "Availability", "Status");
    private final JTable table = new JTable(model);
    private final JTextField search = Theme.field(20);
    private final JLabel info = Theme.muted(" ");
    private List<Exam> all = new ArrayList<Exam>();      // sorted by exam id (as returned by the DAO)
    private List<Exam> shown = new ArrayList<Exam>();

    ExamsTab(AdminController controller, Consumer<Exam> openQuestions) {
        super(new BorderLayout(0, 10));
        this.controller = controller;
        this.openQuestions = openQuestions;
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        top.setOpaque(false);
        top.add(Theme.bold("Search:"));
        top.add(search);
        JButton add = Theme.button("New exam", Theme.Kind.PRIMARY);
        JButton edit = Theme.button("Edit", Theme.Kind.SECONDARY);
        JButton delete = Theme.button("Delete", Theme.Kind.DANGER);
        JButton toggle = Theme.button("Activate / Deactivate", Theme.Kind.SECONDARY);
        JButton questions = Theme.button("Manage questions", Theme.Kind.SUCCESS);
        JButton reload = Theme.button("Refresh", Theme.Kind.SECONDARY);
        for (JButton b : new JButton[]{add, edit, delete, toggle, questions, reload}) top.add(b);
        add(top, BorderLayout.NORTH);

        Theme.styleTable(table);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(info, BorderLayout.SOUTH);

        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        add.addActionListener(e -> new ExamFormDialog(SwingUtilities.getWindowAncestor(this), controller, null, this::refresh).setVisible(true));
        edit.addActionListener(e -> {
            Exam ex = selected();
            if (ex != null) new ExamFormDialog(SwingUtilities.getWindowAncestor(this), controller, ex, this::refresh).setVisible(true);
        });
        delete.addActionListener(e -> deleteSelected());
        toggle.addActionListener(e -> toggleSelected());
        questions.addActionListener(e -> {
            Exam ex = selected();
            if (ex != null) openQuestions.accept(ex);
        });
        reload.addActionListener(e -> refresh());
    }

    private Exam selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= shown.size()) {
            Theme.warn(this, "Please select an exam first.");
            return null;
        }
        return shown.get(row);
    }

    @Override
    public void refresh() {
        final int keepId = table.getSelectedRow() >= 0 && table.getSelectedRow() < shown.size() ? shown.get(table.getSelectedRow()).getExamId() : -1;
        info.setText("Loading...");
        controller.loadExams(exams -> {
            all = exams;
            applyFilter();
            if (keepId >= 0) {
                int idx = SearchUtil.binarySearchById(shown, keepId, Exam::getExamId);   // list is sorted by id
                if (idx >= 0) table.setRowSelectionInterval(idx, idx);
            }
        }, ex -> {
            info.setText(" ");
            Theme.error(this, ex);
        });
    }

    private void applyFilter() {
        shown = SearchUtil.filter(all, search.getText(), e -> e.getTitle() + " " + (e.getDescription() == null ? "" : e.getDescription()));
        model.setRowCount(0);
        for (Exam e : shown) {
            String window = e.getStartTime() == null && e.getEndTime() == null ? "Always"
                    : DateUtil.display(e.getStartTime()) + " to " + DateUtil.display(e.getEndTime());
            model.addRow(new Object[]{e.getExamId(), e.getTitle(), e.getDurationMinutes() + " min",
                    e.getQuestionCount() + " / " + e.getTotalQuestions(), Theme.num(e.getPassingPercentage()),
                    Theme.num(e.getNegativeMarks()), e.getMaxAttempts(), window, e.isActive() ? "Active" : "Inactive"});
        }
        info.setText(shown.size() + " exam(s)");
    }

    private void deleteSelected() {
        final Exam ex = selected();
        if (ex == null) return;
        if (!Theme.confirm(this, "Delete the exam \"" + ex.getTitle() + "\" and all of its questions? This cannot be undone.", "Delete exam")) return;
        controller.deleteExam(ex.getExamId(), this::refresh, e -> Theme.error(this, e));
    }

    private void toggleSelected() {
        final Exam ex = selected();
        if (ex == null) return;
        controller.setExamActive(ex.getExamId(), !ex.isActive(), this::refresh, e -> Theme.error(this, e));
    }
}
