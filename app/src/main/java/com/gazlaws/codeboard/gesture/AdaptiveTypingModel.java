package com.gazlaws.codeboard.gesture;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, local learning from verified user input. Generated corrections are never observations. */
public final class AdaptiveTypingModel {
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    private int revision = -1;
    private Map<String, Integer> counts = Collections.emptyMap();

    public AdaptiveTypingModel(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("adaptive_typing", Context.MODE_PRIVATE);
    }

    public void clear() {
        synchronized (LOCK) {
            preferences.edit().clear().putInt("revision", preferences.getInt("revision", 0) + 1)
                    .putInt("lexicon", preferences.getInt("lexicon", 0) + 1).apply();
            revision = -1;
        }
    }

    public int lexiconVersion() { return preferences.getInt("lexicon", 0); }

    public static String normalize(String text) {
        if (text == null || text.isEmpty() || text.length() > 48) return null;
        String value = text.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (!text.equals(value) && !text.equals(text.toUpperCase(Locale.ROOT))
                && !text.substring(1).equals(text.substring(1).toLowerCase(Locale.ROOT))) return null;
        boolean hangul = value.charAt(0) >= 0xac00 && value.charAt(0) <= 0xd7a3;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (hangul ? c < 0xac00 || c > 0xd7a3 : c < 'a' || c > 'z') {
                if (hangul || c != '\'' || i == 0 || i == value.length() - 1
                        || value.charAt(i - 1) == '\'') return null;
            }
        }
        return value;
    }

    /** At most four preceding words; punctuation, addresses and identifiers break the context. */
    public static List<String> contextWords(String previous) {
        ArrayList<String> result = new ArrayList<>();
        if (previous == null) return result;
        previous = previous.substring(Math.max(previous.lastIndexOf('\n'), previous.lastIndexOf('\r')) + 1);
        String[] parts = previous.trim().split("\\s+");
        for (int i = parts.length - 1; i >= 0 && result.size() < 4; i--) {
            String word = normalize(parts[i]);
            if (word == null) break;
            result.add(0, word);
        }
        return result;
    }

    /** Explicit choices count immediately; an ordinary new word needs three independent commits. */
    public void observe(String previous, String word, boolean explicit) { observe(previous, word, explicit, false); }

    public void observe(String previous, String word, boolean explicit, boolean knownWord) {
        String normalized = normalize(word);
        if (normalized == null || normalized.length() < 2) return;
        synchronized (LOCK) {
            Map<String, Integer> next = new HashMap<>(snapshot());
            int old = get(next, "w:" + normalized);
            increment(next, "w:" + normalized, explicit ? 3 : 1);
            List<String> context = contextWords(previous);
            for (int n = 1; n <= context.size(); n++) {
                increment(next, "p:" + joinTail(context, n) + "\t" + normalized, 1);
            }
            save(next, !knownWord && old < 3 && next.get("w:" + normalized) >= 3);
        }
    }

    public void choose(String previous, String original, String selected) { choose(previous, original, selected, false); }

    public void choose(String previous, String original, String selected, boolean knownWord) {
        String from = normalize(original), to = normalize(selected);
        if (from == null || to == null) return;
        synchronized (LOCK) {
            Map<String, Integer> next = new HashMap<>(snapshot());
            if (!from.equals(to)) {
                increment(next, "c:" + from + "\t" + to, 1);
                String context = android.text.TextUtils.join(" ", contextWords(previous));
                if (!context.isEmpty()) increment(next, "q:" + context + "\t" + from + "\t" + to, 1);
                next.remove("r:" + from + "\t" + to);
            }
            save(next, false);
        }
        observe(previous, selected, true, knownWord);
    }

    public void reject(String original, String replacement) {
        String from = normalize(original), to = normalize(replacement);
        if (from == null || to == null || from.equals(to)) return;
        synchronized (LOCK) {
            Map<String, Integer> next = new HashMap<>(snapshot());
            increment(next, "r:" + from + "\t" + to, 1);
            next.remove("c:" + from + "\t" + to);
            save(next, false);
        }
    }

    public boolean rejected(String original, String candidate) {
        return count("r:" + normalize(original) + "\t" + normalize(candidate)) > 0;
    }

    public int choices(String original, String candidate) {
        return count("c:" + normalize(original) + "\t" + normalize(candidate));
    }

    public int contextualChoices(String previous, String original, String candidate) {
        String context = android.text.TextUtils.join(" ", contextWords(previous));
        return context.isEmpty() ? 0 : count("q:" + context + "\t" + normalize(original) + "\t" + normalize(candidate));
    }

    public double preference(String previous, String candidate) {
        String word = normalize(candidate);
        if (word == null) return 0;
        double score = Math.min(.15, .025 * count("w:" + word));
        List<String> context = contextWords(previous);
        for (int n = 1; n <= context.size(); n++) {
            int seen = count("p:" + joinTail(context, n) + "\t" + word);
            if (seen >= 2) score = Math.max(score, Math.min(.65, .12 * n + .06 * Math.log1p(seen)));
        }
        return score;
    }

    public List<String> predict(String previous, int limit) {
        List<String> context = contextWords(previous);
        ArrayList<String> words = new ArrayList<>();
        Map<String, Integer> data = snapshot();
        for (int n = context.size(); n > 0; n--) {
            String prefix = "p:" + joinTail(context, n) + "\t";
            ArrayList<String> group = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : data.entrySet()) {
                if (entry.getValue() >= 2 && entry.getKey().startsWith(prefix))
                    group.add(entry.getKey().substring(prefix.length()));
            }
            Collections.sort(group, (a, b) -> {
                int order = Integer.compare(data.get(prefix + b), data.get(prefix + a));
                return order != 0 ? order : a.compareTo(b);
            });
            for (String word : group) {
                if (words.size() >= limit) return words;
                if (!words.contains(word)) words.add(word);
            }
        }
        return words;
    }

    public List<SwipeDecoder.Entry> entries(boolean korean) {
        ArrayList<SwipeDecoder.Entry> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : snapshot().entrySet()) {
            if (!entry.getKey().startsWith("w:") || entry.getValue() < 3) continue;
            String word = entry.getKey().substring(2);
            if (normalize(word) == null) continue;
            if ((word.charAt(0) >= 0xac00) == korean)
                result.add(new SwipeDecoder.Entry(word, 10000 + Math.min(100, entry.getValue()) * 1000));
        }
        return result;
    }

    private int count(String key) { return get(snapshot(), key); }

    private Map<String, Integer> snapshot() {
        synchronized (LOCK) {
            int current = preferences.getInt("revision", 0);
            if (revision == current) return counts;
            Map<String, Integer> data = new HashMap<>();
            try {
                JSONObject json = new JSONObject(preferences.getString("counts", "{}"));
                Iterator<String> keys = json.keys();
                while (keys.hasNext() && data.size() < 2400) {
                    String key = keys.next();
                    int value = json.optInt(key, 0);
                    if (key.length() <= 260 && value > 0) data.put(key, Math.min(1000, value));
                }
            } catch (Exception ignored) { /* A damaged local cache must not break typing. */ }
            counts = Collections.unmodifiableMap(data);
            revision = current;
            return counts;
        }
    }

    private void save(Map<String, Integer> data, boolean newWord) {
        if (data.size() > 2000) {
            ArrayList<String> keys = new ArrayList<>(data.keySet());
            Collections.sort(keys, (a, b) -> {
                int order = Integer.compare(data.get(a), data.get(b));
                return order != 0 ? order : a.compareTo(b);
            });
            for (int i = 0; data.size() > 1800; i++) data.remove(keys.get(i));
            newWord = true;
        }
        int next = preferences.getInt("revision", 0) + 1;
        SharedPreferences.Editor editor = preferences.edit().putString("counts", new JSONObject(data).toString())
                .putInt("revision", next);
        if (newWord) editor.putInt("lexicon", lexiconVersion() + 1);
        editor.apply();
        counts = Collections.unmodifiableMap(data);
        revision = next;
    }

    private static int get(Map<String, Integer> data, String key) { Integer value = data.get(key); return value == null ? 0 : value; }

    private static void increment(Map<String, Integer> data, String key, int amount) {
        data.put(key, Math.min(1000, get(data, key) + amount));
    }

    private static String joinTail(List<String> words, int size) {
        return android.text.TextUtils.join(" ", words.subList(words.size() - size, words.size()));
    }
}
