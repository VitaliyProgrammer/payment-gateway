package com.payflow.benchmark;

import java.util.Arrays;

/**
 * Перцентилі з відсортованого масиву значень - метод "nearest rank". Свідомо без
 * HdrHistogram: вибірка бенчмарку - десятки тисяч значень, відсортувати
 * {@code long[]} і взяти елемент за рангом точно й коштує копійки, а залежностей
 * нуль (див. опис модуля).
 */
final class Percentiles {

    private final long[] sorted;

    Percentiles(long[] values) {
        this.sorted = values.clone();
        Arrays.sort(this.sorted);
    }

    int count() {
        return sorted.length;
    }

    long max() {
        return sorted.length == 0 ? 0 : sorted[sorted.length - 1];
    }

    /** {@code p} у діапазоні [0, 100]. */
    long p(double p) {
        if (sorted.length == 0) {
            return 0;
        }
        if (p <= 0) {
            return sorted[0];
        }
        if (p >= 100) {
            return sorted[sorted.length - 1];
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.min(rank, sorted.length) - 1];
    }

    double mean() {
        if (sorted.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return (double) sum / sorted.length;
    }
}
