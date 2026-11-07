package dev.air.browser;

import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class VersesTest {
    private List<Verses.Verse> bundled() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(Path.of("src/main/assets/verses.tsv"), StandardCharsets.UTF_8)) {
            return Verses.parse(reader);
        }
    }

    @Test public void everyBundledVerseHasBothLanguagesAndFitsTheScreen() throws Exception {
        List<Verses.Verse> verses = bundled();
        assertTrue("Expected enough verses to feel different every time: " + verses.size(), verses.size() >= 100);
        Set<String> references = new HashSet<>();
        for (Verses.Verse verse : verses) {
            assertTrue(verse.referenceKo(), references.add(verse.referenceKo()));
            assertTrue(verse.referenceEn(), verse.referenceEn().matches("[1-3 A-Za-z]+ \\d+:\\d+"));
            assertTrue(verse.referenceKo(), verse.referenceKo().matches("[가-힣0-9]+ \\d+:\\d+"));
            assertTrue(verse.referenceKo(), verse.ko().matches(".*[가-힣].*") && verse.ko().length() <= 110);
            assertTrue(verse.referenceEn(), verse.en().matches(".*[A-Za-z].*") && verse.en().length() <= 215);
            // A psalm's title or a translator's note would have come through as markup or brackets.
            assertFalse(verse.en(), verse.en().contains("\\") || verse.en().contains("|"));
            assertEquals(verse.ko(), verse.ko().strip());
            assertEquals(verse.en(), verse.en().strip());
        }
    }

    @Test public void noVerseComesBackUntilAllHaveShown() {
        int size = 50;
        Set<Integer> shown = new HashSet<>();
        for (int position = 0; position < size; position++) shown.add(Verses.indexAt(42L, position, size));
        assertEquals(size, shown.size());
    }

    @Test public void consecutiveLaunchesAlwaysShowADifferentVerse() {
        int size = 50;
        for (long position = 0; position < 3L * size; position++) {
            assertNotEquals(Verses.indexAt(7L, position, size), Verses.indexAt(7L, position + 1, size));
        }
    }

    @Test public void installsShowDifferentOrders() {
        boolean differ = false;
        for (int position = 0; position < 50; position++) {
            differ |= Verses.indexAt(1L, position, 50) != Verses.indexAt(2L, position, 50);
        }
        assertTrue(differ);
    }

    @Test public void aMalformedLineIsRefused() {
        assertThrows(java.io.IOException.class, () -> Verses.parse(new BufferedReader(new StringReader("only\ttwo\n"))));
    }
}
