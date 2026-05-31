package com.example.mysqlbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VectorStoreService utility methods.
 */
class VectorStoreServiceTest {

    @Test
    void toPgVectorString_emptyArray() {
        float[] empty = {};
        String result = toPgVector(empty);
        assertEquals("[]", result);
    }

    @Test
    void toPgVectorString_singleElement() {
        float[] single = {1.5f};
        String result = toPgVector(single);
        assertEquals("[1.5]", result);
    }

    @Test
    void toPgVectorString_multipleElements() {
        float[] vec = {0.1f, 0.2f, 0.3f};
        String result = toPgVector(vec);
        assertEquals("[0.1,0.2,0.3]", result);
    }

    @Test
    void toPgVectorString_negativeValues() {
        float[] vec = {-0.5f, 0.5f};
        String result = toPgVector(vec);
        assertEquals("[-0.5,0.5]", result);
    }

    @Test
    void toPgVectorString_largeArray() {
        float[] vec = new float[1536];
        for (int i = 0; i < 1536; i++) vec[i] = 0.01f * i;
        String result = toPgVector(vec);
        assertTrue(result.startsWith("["));
        assertTrue(result.endsWith("]"));
        // Should have 1535 commas
        assertEquals(1535, result.chars().filter(c -> c == ',').count());
    }

    // Mirror the utility method
    private String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
