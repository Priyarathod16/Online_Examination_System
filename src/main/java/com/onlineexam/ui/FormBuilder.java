package com.onlineexam.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** Minimal two-column label/field form layout. */
final class FormBuilder {
    private final JPanel panel = new JPanel(new GridBagLayout());
    private int row;

    FormBuilder() { panel.setOpaque(false); }

    FormBuilder add(String label, JComponent field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.NORTHWEST;
        l.insets = new Insets(8, 0, 4, 12);
        panel.add(Theme.bold(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.weightx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.insets = new Insets(4, 0, 4, 0);
        panel.add(field, f);
        row++;
        return this;
    }

    FormBuilder addWide(JComponent comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 0, 4, 0);
        panel.add(comp, c);
        return this;
    }

    JPanel build() { return panel; }
}
