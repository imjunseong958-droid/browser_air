package dev.air.browser;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The verses in assets/verses.tsv. Each install walks through its own shuffled order, so no verse comes back until
 * all of them have been shown.
 */
final class Verses {
    record Verse(String referenceKo, String referenceEn, String ko, String en) {}

    private final List<Verse> all;
    private final SharedPreferences progress;

    Verses(Context context) {
        progress = context.getSharedPreferences("verses", Context.MODE_PRIVATE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("verses.tsv"), StandardCharsets.UTF_8))) {
            all = parse(reader);
        } catch (IOException bundledFileMissing) {
            throw new UncheckedIOException(bundledFileMissing);
        }
    }

    static List<Verse> parse(BufferedReader reader) throws IOException {
        List<Verse> verses = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] columns = line.split("\t", -1);
            if (columns.length != 4) throw new IOException("Expected four columns: " + line);
            verses.add(new Verse(columns[0], columns[1], columns[2], columns[3]));
        }
        return verses;
    }

    /** The verse at a position in the order belonging to this seed; the order repeats once every verse has shown. */
    static int indexAt(long seed, long position, int size) {
        List<Integer> order = new ArrayList<>(size);
        for (int i = 0; i < size; i++) order.add(i);
        Collections.shuffle(order, new Random(seed));
        return order.get((int) (position % size));
    }

    Verse get(int index) { return all.get(index % all.size()); }

    int next() {
        if (!progress.contains("seed")) progress.edit().putLong("seed", new Random().nextLong()).apply();
        long position = progress.getLong("position", 0);
        progress.edit().putLong("position", position + 1).apply();
        return indexAt(progress.getLong("seed", 0), position, all.size());
    }
}
