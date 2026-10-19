package com.onlineexam.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Searching helpers: linear search / filtering O(n) and binary search by id O(log n) on sorted lists. */
public final class SearchUtil {
    private SearchUtil() { }

    /** Case-insensitive "contains" filter. A blank keyword returns a copy of the whole list. */
    public static <T> List<T> filter(List<T> items, String keyword, Function<T, String> textOf) {
        List<T> out = new ArrayList<T>();
        String k = keyword == null ? "" : keyword.trim().toLowerCase();
        for (T item : items) {
            if (k.isEmpty()) {
                out.add(item);
            } else {
                String text = textOf.apply(item);
                if (text != null && text.toLowerCase().contains(k)) out.add(item);
            }
        }
        return out;
    }

    /** Linear search; returns the index of the first match or -1. */
    public static <T> int linearSearch(List<T> items, Predicate<T> match) {
        for (int i = 0; i < items.size(); i++) {
            if (match.test(items.get(i))) return i;
        }
        return -1;
    }

    /** Iterative binary search on a list sorted ascending by id; returns the index or -1. */
    public static <T> int binarySearchById(List<T> sortedAscending, int id, ToIntFunction<T> idOf) {
        int low = 0;
        int high = sortedAscending.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midId = idOf.applyAsInt(sortedAscending.get(mid));
            if (midId == id) return mid;
            if (midId < id) low = mid + 1; else high = mid - 1;
        }
        return -1;
    }
}
