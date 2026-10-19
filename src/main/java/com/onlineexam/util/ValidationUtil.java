package com.onlineexam.util;

import com.onlineexam.exception.ValidationException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Reusable input validation helpers. All methods throw {@link ValidationException} with a user-friendly message. */
public final class ValidationUtil {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private ValidationUtil() { }

    /** Administrator username: 3-80 characters, letters, digits, dot, underscore and hyphen only. */
    public static String requireUsername(String value) throws ValidationException {
        String v = requireText(value, "Username", 80);
        if (v.length() < 3) throw new ValidationException("Username must be at least 3 characters long.");
        if (!USERNAME.matcher(v).matches()) {
            throw new ValidationException("Username may contain only letters, digits, dots, underscores and hyphens.");
        }
        return v;
    }

    public static String requireText(String value, String field, int maxLength) throws ValidationException {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) throw new ValidationException(field + " is required.");
        if (v.length() > maxLength) throw new ValidationException(field + " must be at most " + maxLength + " characters.");
        return v;
    }

    public static String optionalText(String value, String field, int maxLength) throws ValidationException {
        String v = value == null ? "" : value.trim();
        if (v.length() > maxLength) throw new ValidationException(field + " must be at most " + maxLength + " characters.");
        return v.isEmpty() ? null : v;
    }

    public static String requireEmail(String value) throws ValidationException {
        String v = requireText(value, "Email", 150).toLowerCase();
        if (!EMAIL.matcher(v).matches()) throw new ValidationException("Please enter a valid email address.");
        return v;
    }

    public static void requirePassword(String password) throws ValidationException {
        if (password == null || password.length() < 8) {
            throw new ValidationException("Password must be at least 8 characters long.");
        }
        if (password.length() > 100) throw new ValidationException("Password is too long (max 100 characters).");
        boolean letter = false;
        boolean digit = false;
        for (char ch : password.toCharArray()) {
            if (Character.isLetter(ch)) letter = true;
            if (Character.isDigit(ch)) digit = true;
        }
        if (!letter || !digit) throw new ValidationException("Password must contain at least one letter and one digit.");
    }

    public static void requireRange(long value, long min, long max, String field) throws ValidationException {
        if (value < min || value > max) {
            throw new ValidationException(field + " must be between " + min + " and " + max + ".");
        }
    }

    public static void requireRange(BigDecimal value, BigDecimal min, BigDecimal max, String field) throws ValidationException {
        if (value == null) throw new ValidationException(field + " is required.");
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new ValidationException(field + " must be between " + min.toPlainString() + " and " + max.toPlainString() + ".");
        }
    }

    public static int parseInt(String text, String field) throws ValidationException {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a whole number.");
        }
    }

    public static BigDecimal parseDecimal(String text, String field) throws ValidationException {
        BigDecimal v;
        try {
            v = new BigDecimal(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a number.");
        }
        if (v.scale() > 2) throw new ValidationException(field + " can have at most 2 decimal places.");
        return v;
    }

    public static boolean isOption(char c) { return c >= 'A' && c <= 'D'; }
}
