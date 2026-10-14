package com.selene.onestepflight;

import java.util.ArrayList;
import java.util.List;

final class SpeechChunks {
    private SpeechChunks() { }
    static List<String> split(String text, int limit) {
        if (limit < 2) throw new IllegalArgumentException("Speech limit must accommodate a Unicode code point");
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + limit, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            result.add(text.substring(start, end)); start = end;
        }
        return result;
    }
}
