package com.onlineexam.service;

/** Tiny dependency-free test harness (no JUnit needed, so tests run with just the JDK). */
final class Harness {
    interface Body {
        void run() throws Exception;
    }

    private static int passed;
    private static int failed;
    private static int skipped;

    private Harness() { }

    static void section(String name) { System.out.println("\n== " + name + " =="); }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL  " + name + "  ->  " + t);
        }
    }

    static void skip(String name, String reason) {
        skipped++;
        System.out.println("  SKIP  " + name + "  (" + reason + ")");
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected <" + expected + "> but was <" + actual + ">)");
        }
    }

    static <T extends Throwable> T expectThrows(Class<T> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " but nothing was thrown");
    }

    /** Prints the totals and returns the process exit code (0 = all good). */
    static int summary() {
        System.out.println("\nResult: " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
        return failed == 0 ? 0 : 1;
    }
}
