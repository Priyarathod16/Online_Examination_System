package com.onlineexam.util;

import java.util.List;
import java.util.Random;

/** Fisher-Yates (Knuth) shuffle: O(n) time, O(1) extra space, uniformly random permutation. */
public final class ShuffleUtil {
    private ShuffleUtil() { }

    public static <T> void shuffle(List<T> list, Random random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);          // 0 <= j <= i
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    public static <T> void shuffle(T[] array, Random random) {
        for (int i = array.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T tmp = array[i];
            array[i] = array[j];
            array[j] = tmp;
        }
    }

    public static void shuffle(int[] array, Random random) {
        for (int i = array.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = array[i];
            array[i] = array[j];
            array[j] = tmp;
        }
    }

    /** Returns a random permutation of 0..n-1. */
    public static int[] permutation(int n, Random random) {
        int[] p = identity(n);
        shuffle(p, random);
        return p;
    }

    public static int[] identity(int n) {
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        return p;
    }
}
