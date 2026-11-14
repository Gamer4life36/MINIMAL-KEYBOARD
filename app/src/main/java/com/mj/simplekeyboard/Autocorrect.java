package com.mj.simplekeyboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline autocorrect over a sorted word list (no Android dependencies, so it can be
 * tested on a desktop JVM).
 *
 * Words are stored compactly: every lowercase key packed into one char[] with offsets,
 * looked up by binary search. Corrections are found by trying single edits (and double
 * edits for longer words) and scoring each real word by its frequency minus the cost
 * of the edit. Cheap edits are the typical touch-typing slips: swapped letters, doubled
 * letters, a missing apostrophe, a neighbouring key.
 */
final class Autocorrect {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz'";
    private static final String[] ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};

    // Edit costs, in the word list's frequency units (0-255, roughly logarithmic).
    private static final int COST_TRANSPOSE = 10;
    private static final int COST_DOUBLE = 3;       // add/remove a repeated letter
    private static final int COST_APOSTROPHE = 2;   // dont -> don't
    private static final int COST_NEIGHBOUR = 35;   // hit the key next to the right one
    private static final int COST_VOWEL = 30;       // a/e/i/o/u mix-up (definately)
    private static final int COST_INSERT = 35;
    private static final int COST_DELETE = 35;
    private static final int COST_SUBSTITUTE = 50;
    private static final int COST_SECOND_EDIT = 15; // extra on top of two edits
    /** A candidate must score at least this to replace what was typed. */
    private static final int MIN_SCORE = 60;

    private char[] blob = new char[0];
    private int[] start = new int[]{0};
    private byte[] freq = new byte[0];
    private int count;
    private final Map<Integer, String> canonical = new HashMap<>();
    private final Map<String, String> shortcuts = new HashMap<>();
    private final boolean[][] neighbours = new boolean[128][128];
    private volatile boolean ready;

    Autocorrect() {
        for (int r = 0; r < ROWS.length; r++) {
            for (int c = 0; c < ROWS[r].length(); c++) {
                char a = ROWS[r].charAt(c);
                for (int r2 = Math.max(0, r - 1); r2 <= Math.min(ROWS.length - 1, r + 1); r2++) {
                    // Rows are offset by about half a key, so check one column either side.
                    for (int c2 = c - 1; c2 <= c + 1; c2++) {
                        if (c2 < 0 || c2 >= ROWS[r2].length()) continue;
                        char b = ROWS[r2].charAt(c2);
                        if (a != b) neighbours[a][b] = true;
                    }
                }
            }
        }
    }

    boolean isReady() {
        return ready;
    }

    /** Loads words.txt (key TAB freq [TAB canonical], sorted by key) and shortcuts.txt. */
    void load(BufferedReader words, BufferedReader shortcutList) throws IOException {
        StringBuilder keys = new StringBuilder(1 << 21);
        int[] offs = new int[1 << 18];
        byte[] fr = new byte[1 << 18];
        int n = 0;
        String line;
        while ((line = words.readLine()) != null) {
            int t1 = line.indexOf('\t');
            if (t1 <= 0) continue;
            int t2 = line.indexOf('\t', t1 + 1);
            if (n + 2 > offs.length) {
                offs = java.util.Arrays.copyOf(offs, offs.length * 2);
                fr = java.util.Arrays.copyOf(fr, fr.length * 2);
            }
            offs[n] = keys.length();
            keys.append(line, 0, t1);
            fr[n] = (byte) Integer.parseInt(t2 < 0 ? line.substring(t1 + 1) : line.substring(t1 + 1, t2));
            if (t2 > 0) canonical.put(n, line.substring(t2 + 1));
            n++;
        }
        offs[n] = keys.length();
        while ((line = shortcutList.readLine()) != null) {
            int t = line.indexOf('\t');
            if (t > 0) shortcuts.put(line.substring(0, t), line.substring(t + 1));
        }
        blob = new char[keys.length()];
        keys.getChars(0, keys.length(), blob, 0);
        start = java.util.Arrays.copyOf(offs, n + 1);
        freq = java.util.Arrays.copyOf(fr, n);
        count = n;
        ready = true;
    }

    /** Index of a lowercase key, or -1. */
    private int find(CharSequence key) {
        int lo = 0, hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compare(mid, key);
            if (cmp == 0) return mid;
            if (cmp < 0) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    private int compare(int i, CharSequence key) {
        int s = start[i], e = start[i + 1];
        int len = Math.min(e - s, key.length());
        for (int k = 0; k < len; k++) {
            int d = blob[s + k] - key.charAt(k);
            if (d != 0) return d;
        }
        return (e - s) - key.length();
    }

    private String wordAt(int i) {
        String c = canonical.get(i);
        return c != null ? c : new String(blob, start[i], start[i + 1] - start[i]);
    }

    private int freqAt(int i) {
        return freq[i] & 0xFF;
    }

    /**
     * Returns what {@code typed} should become, or null to leave it alone.
     */
    String correct(String typed) {
        if (!ready || typed.isEmpty() || typed.length() > 24) return null;
        for (int i = 0; i < typed.length(); i++) {
            char ch = typed.charAt(i);
            if (!(Character.isLetter(ch) && ch < 128) && ch != '\'') return null;
        }
        String key = typed.toLowerCase(Locale.US);

        boolean allCaps = typed.length() > 1 && typed.equals(typed.toUpperCase(Locale.US));
        if (allCaps) return null; // acronyms and shouting: leave as typed
        // Mixed case like "iPhone" or "McKay": the user meant it.
        for (int i = 1; i < typed.length(); i++) {
            if (Character.isUpperCase(typed.charAt(i))) return null;
        }

        String sc = shortcuts.get(key);
        if (sc != null) return sameOrNull(typed, matchCase(typed, sc));

        int idx = find(key);
        if (idx >= 0) {
            // Known word. Only fix capitalisation of names that are always capitalised
            // ("london" -> "London"), typed all lowercase. Never turn a lowercase word
            // into an acronym ("ok" -> "OK", "lst" -> "LST").
            String canon = canonical.get(idx);
            if (canon != null && typed.equals(key) && isCapitalised(canon)) return canon;
            return null;
        }
        if (key.length() < 2) return null;

        Best best = new Best();
        edits(key, 0, best);
        if (best.index < 0 && key.length() >= 4 && key.length() <= 10) {
            // Nothing one edit away: try two edits, using each one-edit string as a base.
            // Capped at 10 letters: this search grows fast and runs on the UI thread.
            Best second = new Best();
            for (String base : oneEditStrings(key)) {
                edits(base.substring(0, base.length() - 2),
                        Integer.parseInt(base.substring(base.length() - 2).trim())
                                + COST_SECOND_EDIT, second);
            }
            best = second;
        }
        if (best.index < 0 || best.score < MIN_SCORE) return null;
        return sameOrNull(typed, matchCase(typed, wordAt(best.index)));
    }

    /** "London" yes; "OK", "iPhone", "McKay" no. */
    private static boolean isCapitalised(String w) {
        if (w.isEmpty() || !Character.isUpperCase(w.charAt(0))) return false;
        for (int i = 1; i < w.length(); i++) {
            if (Character.isUpperCase(w.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    private int substituteCost(char from, char to) {
        if (neighbours[from][to]) return COST_NEIGHBOUR;
        if (isVowel(from) && isVowel(to)) return COST_VOWEL;
        return COST_SUBSTITUTE;
    }

    private static String sameOrNull(String typed, String fixed) {
        return fixed.equals(typed) ? null : fixed;
    }

    /** Carries the typed word's leading capital over to the correction. */
    private static String matchCase(String typed, String word) {
        if (Character.isUpperCase(typed.charAt(0)) && !word.isEmpty()
                && Character.isLowerCase(word.charAt(0))) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return word;
    }

    private static final class Best {
        int index = -1;
        int score = Integer.MIN_VALUE;

        void offer(int idx, int score) {
            if (score > this.score) {
                this.index = idx;
                this.score = score;
            }
        }
    }

    private void consider(CharSequence s, int cost, Best best) {
        int idx = find(s);
        if (idx < 0) return;
        int f = freqAt(idx);
        if (f == 0) return; // offensive / never-suggest words
        best.offer(idx, f - cost);
    }

    /** Tries every single edit of {@code w}; each found word is scored. */
    private void edits(String w, int baseCost, Best best) {
        int n = w.length();
        StringBuilder sb = new StringBuilder(n + 1);
        // Deletions: the user typed an extra letter.
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            int cost = c == '\'' ? COST_APOSTROPHE
                    : (i > 0 && w.charAt(i - 1) == c) || (i + 1 < n && w.charAt(i + 1) == c)
                    ? COST_DOUBLE : COST_DELETE;
            sb.setLength(0);
            sb.append(w, 0, i).append(w, i + 1, n);
            if (sb.length() > 0) consider(sb, baseCost + cost, best);
        }
        // Transpositions.
        for (int i = 0; i + 1 < n; i++) {
            if (w.charAt(i) == w.charAt(i + 1)) continue;
            sb.setLength(0);
            sb.append(w);
            sb.setCharAt(i, w.charAt(i + 1));
            sb.setCharAt(i + 1, w.charAt(i));
            consider(sb, baseCost + COST_TRANSPOSE, best);
        }
        // Substitutions.
        for (int i = 0; i < n; i++) {
            char orig = w.charAt(i);
            for (int k = 0; k < ALPHABET.length(); k++) {
                char c = ALPHABET.charAt(k);
                if (c == orig || c == '\'' || orig == '\'') continue;
                sb.setLength(0);
                sb.append(w);
                sb.setCharAt(i, c);
                consider(sb, baseCost + substituteCost(orig, c), best);
            }
        }
        // Insertions: the user missed a letter (or the apostrophe).
        for (int i = 0; i <= n; i++) {
            for (int k = 0; k < ALPHABET.length(); k++) {
                char c = ALPHABET.charAt(k);
                if (c == '\'' && (i == 0 || i == n)) continue;
                int cost = c == '\'' ? COST_APOSTROPHE
                        : (i > 0 && w.charAt(i - 1) == c) || (i < n && w.charAt(i) == c)
                        ? COST_DOUBLE : COST_INSERT;
                sb.setLength(0);
                sb.append(w, 0, i).append(c).append(w, i, n);
                consider(sb, baseCost + cost, best);
            }
        }
    }

    /** Every single-edit string of {@code w}, each suffixed with its 2-digit cost. */
    private Set<String> oneEditStrings(String w) {
        Set<String> out = new HashSet<>();
        int n = w.length();
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            int cost = (i > 0 && w.charAt(i - 1) == c) || (i + 1 < n && w.charAt(i + 1) == c)
                    ? COST_DOUBLE : COST_DELETE;
            if (n > 1) out.add(w.substring(0, i) + w.substring(i + 1) + pad(cost));
        }
        for (int i = 0; i + 1 < n; i++) {
            char[] a = w.toCharArray();
            char t = a[i]; a[i] = a[i + 1]; a[i + 1] = t;
            out.add(new String(a) + pad(COST_TRANSPOSE));
        }
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < ALPHABET.length() - 1; k++) {
                char c = ALPHABET.charAt(k);
                if (c == w.charAt(i) || w.charAt(i) == '\'') continue;
                out.add(w.substring(0, i) + c + w.substring(i + 1)
                        + pad(substituteCost(w.charAt(i), c)));
            }
        }
        for (int i = 0; i <= n; i++) {
            for (int k = 0; k < ALPHABET.length() - 1; k++) {
                char c = ALPHABET.charAt(k);
                int cost = (i > 0 && w.charAt(i - 1) == c) || (i < n && w.charAt(i) == c)
                        ? COST_DOUBLE : COST_INSERT;
                out.add(w.substring(0, i) + c + w.substring(i) + pad(cost));
            }
        }
        return out;
    }

    private static String pad(int cost) {
        return cost < 10 ? " " + cost : Integer.toString(cost);
    }
}
