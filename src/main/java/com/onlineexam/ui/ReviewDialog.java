package com.onlineexam.ui;

import com.onlineexam.model.ReviewItem;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Window;
import java.util.List;

/** Post-submission answer review: shows the student's choice and the correct option for each question. */
class ReviewDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    ReviewDialog(Window owner, String examTitle, List<ReviewItem> items) {
        super(owner, "Answer review - " + examTitle, ModalityType.APPLICATION_MODAL);
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(Theme.BG);
        list.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        for (ReviewItem item : items) {
            JPanel card = Theme.card();
            card.setLayout(new BorderLayout(0, 8));
            String outcome = item.getSelected() == null ? "Not answered" : item.isCorrect() ? "Correct" : "Incorrect";
            JLabel head = Theme.bold("Question " + item.getDisplayOrder() + "  -  " + outcome + "  (" + Theme.num(item.getMarks()) + " marks)");
            head.setForeground(item.isCorrect() ? Theme.SUCCESS : item.getSelected() == null ? Theme.WARN : Theme.DANGER);
            card.add(head, BorderLayout.NORTH);

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            JTextArea text = new JTextArea(item.getText());
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setOpaque(false);
            text.setFont(Theme.QUESTION);
            body.add(text);
            for (int k = 0; k < item.getOptionTexts().length; k++) {
                char letter = item.getOptionLetters()[k];
                boolean isCorrect = letter == item.getCorrect();
                boolean isChosen = item.getSelected() != null && item.getSelected().charValue() == letter;
                String suffix = isCorrect && isChosen ? "   [your answer - correct]" : isCorrect ? "   [correct answer]"
                        : isChosen ? "   [your answer]" : "";
                JLabel opt = new JLabel("  " + (char) ('A' + k) + ".  " + item.getOptionTexts()[k] + suffix);
                opt.setOpaque(true);
                opt.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
                opt.setBackground(isCorrect ? Theme.SUCCESS_BG : isChosen ? Theme.DANGER_BG : Color.WHITE);
                opt.setForeground(Theme.TEXT);
                body.add(opt);
            }
            card.add(body, BorderLayout.CENTER);
            list.add(card);
            list.add(javax.swing.Box.createVerticalStrut(10));
        }
        JScrollPane scroll = new JScrollPane(list);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        setSize(780, 640);
        setLocationRelativeTo(owner);
    }
}
