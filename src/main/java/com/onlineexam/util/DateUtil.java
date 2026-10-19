package com.onlineexam.util;

import com.onlineexam.exception.ValidationException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Date/time formatting and parsing used by the UI. */
public final class DateUtil {
    public static final String INPUT_PATTERN = "yyyy-MM-dd HH:mm";
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern(INPUT_PATTERN);
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private DateUtil() { }

    public static String display(LocalDateTime t) { return t == null ? "-" : DISPLAY.format(t); }

    public static String input(LocalDateTime t) { return t == null ? "" : INPUT.format(t); }

    /** Blank text means "no value". */
    public static LocalDateTime parseOptional(String text, String field) throws ValidationException {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return LocalDateTime.parse(text.trim(), INPUT);
        } catch (DateTimeParseException e) {
            throw new ValidationException(field + " must use the format " + INPUT_PATTERN + " (for example 2026-12-31 18:30).");
        }
    }

    public static String formatSeconds(long totalSeconds) {
        long s = Math.max(0, totalSeconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }
}
