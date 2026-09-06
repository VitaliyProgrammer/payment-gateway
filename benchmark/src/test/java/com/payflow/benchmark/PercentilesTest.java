package com.payflow.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class PercentilesTest {

    @Test
    void nearestRankOnOneToHundred() {
        Percentiles p = new Percentiles(LongStream.rangeClosed(1, 100).toArray());

        assertEquals(100, p.count());
        assertEquals(1, p.p(0));
        assertEquals(50, p.p(50));
        assertEquals(90, p.p(90));
        assertEquals(99, p.p(99));
        assertEquals(100, p.p(100));
        assertEquals(100, p.max());
        assertEquals(50.5, p.mean(), 1e-9);
    }

    @Test
    void emptySampleIsAllZero() {
        Percentiles p = new Percentiles(new long[0]);

        assertEquals(0, p.count());
        assertEquals(0, p.p(50));
        assertEquals(0, p.max());
        assertEquals(0.0, p.mean(), 1e-9);
    }

    @Test
    void unsortedInputIsHandled() {
        Percentiles p = new Percentiles(new long[] {9, 1, 7, 3, 5});

        assertEquals(1, p.p(0));
        assertEquals(5, p.p(50));
        assertEquals(9, p.max());
    }
}
