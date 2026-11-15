package com.gazlaws.codeboard.gesture;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Per-layout key-center offsets trained only from explicitly confirmed spellings. */
public final class TouchModel {
    private final SharedPreferences preferences;
    public TouchModel(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("touch_model", Context.MODE_PRIVATE);
    }
    public void clear() { preferences.edit().clear().apply(); }

    public static final class Sample {
        public final char letter;
        public final float x, y;
        public final String profile;
        public final List<SwipeDecoder.KeyPosition> keys;
        public Sample(char letter, float x, float y, String profile, List<SwipeDecoder.KeyPosition> keys) {
            this.letter = letter; this.x = x; this.y = y; this.profile = profile;
            this.keys = new ArrayList<>(keys);
        }
    }

    public void learn(List<Sample> samples, String chosen) {
        String sequence = SwipeDecoder.keySequenceForWord(chosen);
        if (sequence == null || sequence.length() != samples.size() || samples.isEmpty()) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            char letter = Character.toLowerCase(sequence.charAt(i));
            SwipeDecoder.KeyPosition key = key(sample, letter);
            if (key == null || key.width <= 0 || key.height <= 0) continue;
            float dx = (sample.x - key.centerX) / key.width, dy = (sample.y - key.centerY) / key.height;
            if (Float.isNaN(dx) || Float.isInfinite(dx) || Float.isNaN(dy) || Float.isInfinite(dy) || Math.abs(dx) > .8 || Math.abs(dy) > .8) continue;
            String prefix = sample.profile + ":" + letter;
            int count = Math.min(40, preferences.getInt(prefix + "n", 0));
            float weight = 1f / (count + 1);
            float x = preferences.getFloat(prefix + "x", 0) * (1 - weight) + dx * weight;
            float y = preferences.getFloat(prefix + "y", 0) * (1 - weight) + dy * weight;
            editor.putInt(prefix + "n", count + 1).putFloat(prefix + "x", clamp(x)).putFloat(prefix + "y", clamp(y));
        }
        editor.apply();
    }

    /** Relative likelihood evidence, used for ranking rather than forcibly remapping taps. */
    public double cost(List<Sample> samples, String candidate) {
        String sequence = SwipeDecoder.keySequenceForWord(candidate);
        if (sequence == null || sequence.length() != samples.size() || samples.isEmpty()) return Double.NaN;
        double cost = 0;
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            char letter = Character.toLowerCase(sequence.charAt(i));
            SwipeDecoder.KeyPosition key = key(sample, letter);
            if (key == null || key.width <= 0 || key.height <= 0) return Double.NaN;
            String prefix = sample.profile + ":" + letter;
            float confidence = Math.min(1f, preferences.getInt(prefix + "n", 0) / 8f);
            double dx = (sample.x - key.centerX) / key.width - confidence * preferences.getFloat(prefix + "x", 0);
            double dy = (sample.y - key.centerY) / key.height - confidence * preferences.getFloat(prefix + "y", 0);
            cost += Math.min(4, dx * dx + dy * dy);
        }
        return cost / samples.size();
    }

    private static float clamp(float value) { return Math.max(-.3f, Math.min(.3f, value)); }
    private static SwipeDecoder.KeyPosition key(Sample sample, char letter) {
        for (SwipeDecoder.KeyPosition key : sample.keys) if (key.key == letter) return key;
        return null;
    }
}
