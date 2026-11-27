package com.threadfit.glucopilot.food.gi;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SydneyGiLookupClientTest {
    private SydneyGiLookupClient.Entry entry(String name, String gi, String brand, String subjects, int n) {
        return new SydneyGiLookupClient.Entry(name, new BigDecimal(gi), brand, subjects, n, "Test study");
    }

    @Test void exactHealthyUnbrandedStudyProvidesReferenceAndSource() {
        var result = SydneyGiLookupClient.match(List.of(entry("Apple, raw", "36", "", "Normal", 12)), "APPLE, RAW");
        assertEquals(new BigDecimal("36"), result.value());
        assertEquals("REFERENCE", result.status());
        assertEquals(SydneyGiLookupClient.SOURCE, result.sourceUrl());
        assertTrue(result.note().contains("不代表当前食品实测"));
    }

    @Test void doesNotMixCookingMethodsBrandsOrDiabetesStudyGroups() {
        var entries = List.of(entry("Potato, boiled", "66.4", "", "Normal", 12),
                entry("Potato", "60", "Brand", "Normal", 12),
                entry("Potato", "61", "", "Type 2", 12),
                entry("Potato", "62", "", "Normal", 8));
        assertNull(SydneyGiLookupClient.match(entries, "Potato").value());
        assertNull(SydneyGiLookupClient.match(entries, "Potato, fried").value());
    }

    @Test void conflictingValuesRemainUnknownInsteadOfTakingFirstOrMean() {
        var entries = List.of(entry("Food", "40", "", "Normal", 12), entry("Food", "60", "", "Normal", 12));
        assertNull(SydneyGiLookupClient.match(entries, "Food").value());
        assertEquals("NOT_FOUND", SydneyGiLookupClient.match(entries, "Food").status());
    }

    @Test void parserUsesActualGiColumnAndDoesNotTreatCarbsOrMalformedValuesAsGi() throws Exception {
        String row = "<tr><td>Food &amp; fruit</td><td>%s</td><td></td><td>Fruit</td><td>Test</td>"
                + "<td>100</td><td>20</td><td>7</td><td>Reference</td><td>Normal</td><td>2h</td><td>12</td><td>2020</td></tr>";
        var entries = SydneyGiLookupClient.parse("<table>" + row.formatted("36") + row.formatted("30-40")
                + row.formatted("0") + row.formatted("999") + "</table>");
        assertEquals(1, entries.size());
        assertEquals("Food & fruit", entries.getFirst().name());
        assertEquals(new BigDecimal("36"), entries.getFirst().value());
        assertEquals(12, entries.getFirst().sampleSize());
    }
}
