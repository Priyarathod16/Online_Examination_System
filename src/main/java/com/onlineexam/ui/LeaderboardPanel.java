package com.onlineexam.ui;

import com.onlineexam.model.Exam;
import com.onlineexam.model.LeaderboardEntry;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;

/** Exam picker plus ranked table. Data sources are injected so students and admins can share it. */
class LeaderboardPanel extends JPanel implements Refreshable {
    private static final long serialVersionUID = 1L;

    interface ExamSource {
        void load(Consumer<List<Exam>> ok, Consumer<Exception> err);
    }

    interface RankingSource {
        void load(int examId, Consumer<List<LeaderboardEntry>> ok, Consumer<Exception> err);
    }

    private final ExamSource examSource;
    private final RankingSource rankingSource;
    private final JComboBox<Exam> examCombo = new JComboBox<Exam>();
    private final DefaultTableModel model = Theme.readOnlyModel("Rank", "Student", "Score", "Max", "Percentage", "Result", "Submitted");
    private final JLabel info = Theme.muted(" ");
    private boolean loading;

    LeaderboardPanel(ExamSource examSource, RankingSource rankingSource) {
        super(new BorderLayout(0, 10));
        this.examSource = examSource;
        this.rankingSource = rankingSource;
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        top.setOpaque(false);
        top.add(Theme.bold("Exam:"));
        examCombo.setPreferredSize(new java.awt.Dimension(320, 32));
        top.add(examCombo);
        top.add(info);
        add(top, BorderLayout.NORTH);

        JTable table = new JTable(model);
        Theme.styleTable(table);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(Theme.muted("Each student appears once with their best attempt. Ties are broken by percentage, then by earlier submission."),
                BorderLayout.SOUTH);

        examCombo.addActionListener(e -> {
            if (!loading) loadRanking();
        });
    }

    @Override
    public void refresh() {
        final Exam previous = (Exam) examCombo.getSelectedItem();
        examSource.load(exams -> {
            loading = true;
            examCombo.removeAllItems();
            Exam toSelect = null;
            for (Exam e : exams) {
                examCombo.addItem(e);
                if (previous != null && previous.getExamId() == e.getExamId()) toSelect = e;
            }
            if (toSelect != null) examCombo.setSelectedItem(toSelect);
            loading = false;
            loadRanking();
        }, ex -> Theme.error(this, ex));
    }

    private void loadRanking() {
        model.setRowCount(0);
        Exam exam = (Exam) examCombo.getSelectedItem();
        if (exam == null) {
            info.setText("No exams available.");
            return;
        }
        info.setText("Loading...");
        rankingSource.load(exam.getExamId(), entries -> {
            model.setRowCount(0);
            for (LeaderboardEntry e : entries) {
                model.addRow(new Object[]{e.getRank(), e.getStudentName(), Theme.num(e.getScore()), Theme.num(e.getMaxScore()),
                        Theme.num(e.getPercentage()) + "%", e.getStatus(), com.onlineexam.util.DateUtil.display(e.getSubmittedAt())});
            }
            info.setText(entries.isEmpty() ? "No results yet for this exam." : entries.size() + " student(s) ranked");
        }, ex -> {
            info.setText(" ");
            Theme.error(this, ex);
        });
    }
}
