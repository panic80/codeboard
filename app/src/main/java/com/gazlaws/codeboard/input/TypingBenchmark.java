package com.gazlaws.codeboard.input;

/** Reference-based final-text error rates. No claims about an untested keyboard's accuracy. */
public final class TypingBenchmark {
    private TypingBenchmark() { }
    public static final class Result {
        public final int characterErrors, wordErrors;
        public final double characterErrorRate, wordErrorRate, wordsPerMinute;
        Result(int chars, int words, int refChars, int refWords, int typedChars, long elapsedMillis) {
            characterErrors = chars; wordErrors = words;
            characterErrorRate = 100d * chars / Math.max(1, refChars);
            wordErrorRate = 100d * words / Math.max(1, refWords);
            wordsPerMinute = typedChars * 12000d / Math.max(1000, elapsedMillis);
        }
    }
    public static Result score(String expected, String actual, long elapsedMillis) {
        String[] referenceChars = characters(expected), actualChars = characters(actual);
        String[] referenceWords = words(expected), actualWords = words(actual);
        return new Result(distance(referenceChars, actualChars), distance(referenceWords, actualWords),
                referenceChars.length, referenceWords.length, actualChars.length, elapsedMillis);
    }
    private static String[] words(String text) { return text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+"); }
    private static String[] characters(String text) {
        String[] result = new String[text.codePointCount(0, text.length())];
        for (int i=0, j=0; i<text.length(); j++) { int cp=text.codePointAt(i); result[j]=new String(Character.toChars(cp)); i+=Character.charCount(cp); }
        return result;
    }
    private static int distance(String[] reference, String[] actual) {
        int[] previous=new int[actual.length+1];
        for(int j=0;j<previous.length;j++)previous[j]=j;
        for(int i=1;i<=reference.length;i++) {
            int[] next=new int[previous.length]; next[0]=i;
            for(int j=1;j<next.length;j++)next[j]=Math.min(Math.min(previous[j]+1,next[j-1]+1),
                    previous[j-1]+(reference[i-1].equals(actual[j-1])?0:1));
            previous=next;
        }
        return previous[actual.length];
    }
}
