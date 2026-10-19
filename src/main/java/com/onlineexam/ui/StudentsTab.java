package com.onlineexam.ui;

import com.onlineexam.controller.AdminController;
import com.onlineexam.model.Student;
import com.onlineexam.util.DateUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/** Registered students with a database-side (SQL LIKE) search. */
class StudentsTab extends JPanel implements Refreshable {
    private static final long serialVersionUID = 1L;

    private final AdminController controller;
    private final DefaultTableModel model = Theme.readOnlyModel("ID", "Name", "Email", "Registered", "Attempts");
    private final JTextField search = Theme.field(24);
    private final JLabel info = Theme.muted(" ");

    StudentsTab(AdminController controller) {
        super(new BorderLayout(0, 10));
        this.controller = controller;
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        top.setOpaque(false);
        top.add(Theme.bold("Name or email:"));
        top.add(search);
        JButton go = Theme.button("Search", Theme.Kind.PRIMARY);
        top.add(go);
        add(top, BorderLayout.NORTH);
        JTable table = new JTable(model);
        Theme.styleTable(table);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(info, BorderLayout.SOUTH);
        go.addActionListener(e -> refresh());
        search.addActionListener(e -> refresh());
    }

    @Override
    public void refresh() {
        info.setText("Searching...");
        controller.searchStudents(search.getText(), list -> {
            model.setRowCount(0);
            for (Student s : list) {
                model.addRow(new Object[]{s.getStudentId(), s.getName(), s.getEmail(), DateUtil.display(s.getCreatedAt()), s.getAttemptCount()});
            }
            info.setText(list.size() + " student(s)");
        }, ex -> {
            info.setText(" ");
            Theme.error(this, ex);
        });
    }
}
