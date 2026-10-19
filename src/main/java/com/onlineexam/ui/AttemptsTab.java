package com.onlineexam.ui;

import com.onlineexam.controller.AdminController;
import com.onlineexam.model.AttemptSummary;
import com.onlineexam.model.Exam;
import com.onlineexam.model.ResultStatus;
import com.onlineexam.util.DateUtil;
import com.onlineexam.util.SearchUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Student attempts and results with a class-level summary. */
class AttemptsTab extends JPanel implements Refreshable {
    private static final long serialVersionUID = 1L;

    private final AdminController controller;
    private final JComboBox<Exam> examCombo = new JComboBox<Exam>();
    private final DefaultTableModel model = Theme.readOnlyModel("Attempt", "Student", "Email", "Exam", "No.", "Started",
            "Submitted", "Status", "Score", "Percentage", "Result");
    private final JTextField search = Theme.field(18);
    private final JLabel summary = Theme.bold(" ");
    private List<AttemptSummary> all = new ArrayList<AttemptSummary>();
    private boolean loading;

    AttemptsTab(AdminController controller) {
        super(new BorderLayout(0, 10));
        this.controller = controller;
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        top.setOpaque(false);
        top.add(Theme.bold("Exam:"));
        examCombo.setPreferredSize(new java.awt.Dimension(280, 32));
        top.add(examCombo);
        top.add(Theme.bold("Search student:"));
        top.add(search);
        JButton reload = Theme.button("Refresh", Theme.Kind.SECONDARY);
        top.add(reload);
        add(top, BorderLayout.NORTH);

        JTable table = new JTable(model);
        Theme.styleTable(table);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);

        examCombo.addActionListener(e -> {
            if (!loading) loadAttempts();
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        reload.addActionListener(e -> refresh());
    }

    @Override
    public void refresh() {
        Exam prev = (Exam) examCombo.getSelectedItem();
        final int keep = prev == null ? 0 : prev.getExamId();
        controller.loadExams(list -> {
            loading = true;
            examCombo.removeAllItems();
            Exam everything = new Exam();
            everything.setExamId(0);
            everything.setTitle("All exams");
            examCombo.addItem(everything);
            for (Exam e : list) examCombo.addItem(e);
            for (int i = 0; i < examCombo.getItemCount(); i++) {
                if (examCombo.getItemAt(i).getExamId() == keep) examCombo.setSelectedIndex(i);
            }
            loading = false;
            loadAttempts();
        }, ex -> Theme.error(this, ex));
    }

    private void loadAttempts() {
        Exam sel = (Exam) examCombo.getSelectedItem();
        Integer examId = sel == null || sel.getExamId() == 0 ? null : Integer.valueOf(sel.getExamId());
        summary.setText("Loading...");
        controller.loadAttempts(examId, list -> {
            all = list;
            applyFilter();
        }, ex -> {
            summary.setText(" ");
            Theme.error(this, ex);
        });
    }

    private void applyFilter() {
        List<AttemptSummary> shown = SearchUtil.filter(all, search.getText(), a -> a.getStudentName() + " " + a.getStudentEmail());
        model.setRowCount(0);
        int finished = 0;
        int passed = 0;
        BigDecimal pctSum = BigDecimal.ZERO;
        for (AttemptSummary a : shown) {
            model.addRow(new Object[]{a.getAttemptId(), a.getStudentName(), a.getStudentEmail(), a.getExamTitle(), a.getAttemptNo(),
                    DateUtil.display(a.getStartedAt()), DateUtil.display(a.getSubmittedAt()), a.getStatus(),
                    a.getScore() == null ? "-" : Theme.num(a.getScore()) + " / " + Theme.num(a.getMaxScore()),
                    a.getPercentage() == null ? "-" : Theme.num(a.getPercentage()) + "%",
                    a.getResultStatus() == null ? "-" : a.getResultStatus()});
            if (a.getPercentage() != null) {
                finished++;
                pctSum = pctSum.add(a.getPercentage());
                if (a.getResultStatus() == ResultStatus.PASS) passed++;
            }
        }
        String avg = finished == 0 ? "-" : pctSum.divide(BigDecimal.valueOf(finished), 2, RoundingMode.HALF_UP).toPlainString() + "%";
        String rate = finished == 0 ? "-" : (passed * 100 / finished) + "%";
        summary.setText(shown.size() + " attempt(s)  |  " + finished + " finished  |  average score " + avg + "  |  pass rate " + rate);
    }
}
