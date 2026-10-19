package com.onlineexam.app;

import com.onlineexam.ui.MainFrame;
import com.onlineexam.ui.Theme;

import javax.swing.SwingUtilities;

/** Application entry point: starts the Swing UI on the event-dispatch thread. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            new MainFrame().setVisible(true);
        });
    }
}
