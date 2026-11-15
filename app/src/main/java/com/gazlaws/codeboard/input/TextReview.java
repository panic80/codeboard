package com.gazlaws.codeboard.input;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit, previewable repairs for a small set of common English/Korean constructions. */
public final class TextReview {
    private TextReview() { }
    public static String review(String original, boolean chatSpacing) {
        if (original == null || original.length() > 2000) return original;
        // A review of prose must not rewrite addresses, URLs or source expressions.
        if (original.matches("(?s).*[ @][^\\s]+@[^\\s]+.*") || original.contains("@")
                || original.contains("://") || original.matches("(?s).*[{}_=<>\\\\`].*")) return original;
        String result = original;
        String[][] rules = {
                {"alot", "a lot"}, {"aswell", "as well"}, {"infront", "in front"},
                {"eachother", "each other"}, {"could of", "could have"},
                {"would of", "would have"}, {"should of", "should have"},
                {"i is", "I am"}, {"i are", "I am"}, {"we is", "we are"},
                {"they is", "they are"}, {"you is", "you are"},
                {"he are", "he is"}, {"she are", "she is"},
                {"dont", "don't"}, {"doesnt", "doesn't"}, {"didnt", "didn't"},
                {"isnt", "isn't"}, {"wasnt", "wasn't"}, {"couldnt", "couldn't"}
        };
        for (String[] rule : rules) result = replaceWords(result, rule[0], rule[1]);
        result = result.replaceAll("(?iu)\\b(the|a|an|to|of)\\s+\\1\\b", "$1");
        result = result.replace("할수 있어", "할 수 있어").replace("할수있", "할 수 있")
                .replace("할것 같", "할 것 같").replace("할것같", "할 것 같")
                .replace("안되요", "안 돼요").replace("안돼요", "안 돼요")
                .replace("되요", "돼요").replace("몇시에", "몇 시에")
                .replace("할 수 없읍니다", "할 수 없습니다");
        if (chatSpacing) result = result.replaceAll("(?<=[A-Za-z가-힣]);(?=[A-Za-z가-힣])", " ");
        return result;
    }

    private static String replaceWords(String input, String from, String to) {
        Matcher matcher = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(from)
                + "(?![\\p{L}\\p{N}_])").matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String replacement = to;
            if (Character.isUpperCase(matcher.group().charAt(0)))
                replacement = Character.toUpperCase(to.charAt(0)) + to.substring(1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
