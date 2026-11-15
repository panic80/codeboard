package com.gazlaws.codeboard.input;

import static org.junit.Assert.*;
import org.junit.Test;

public class TypingImprovementsTest {
    @Test public void reviewRepairsEnglishGrammarAndMissingBoundaries() {
        assertEquals("I am going to see a lot of people. We should have called.",
                TextReview.review("I is going to see alot of people. We should of called.", false));
    }
    @Test public void reviewRepairsSupportedKoreanSpacing() {
        assertEquals("몇 시에 시작할 수 있나요? 안 돼요.",TextReview.review("몇시에 시작할수있나요? 안되요.",false));
    }
    @Test public void semicolonRepairRequiresExplicitChatSetting() {
        assertEquals("please;help;me",TextReview.review("please;help;me",false));
        assertEquals("please help me",TextReview.review("please;help;me",true));
    }
    @Test public void codeEmailAndUrlsStayLiteralEvenInReview() {
        for(String text:new String[]{"let x = 'alot';", "person@example.com", "https://example.com/alot"})
            assertEquals(text,TextReview.review(text,true));
    }
    @Test public void validRepetitionsAndEmbeddedWordsStayUnchanged() {
        assertEquals("I had had enough of the allotment.",TextReview.review("I had had enough of the allotment.",false));
    }
    @Test public void benchmarkReportsKnownWordAndCharacterErrors() {
        TypingBenchmark.Result result=TypingBenchmark.score("this is a test","this is a tent",60000);
        assertEquals(1,result.characterErrors); assertEquals(1,result.wordErrors);
        assertEquals(25,result.wordErrorRate,.001); assertEquals(2.8,result.wordsPerMinute,.001);
    }
    @Test public void benchmarkHandlesKoreanAndSupplementaryCharacters() {
        TypingBenchmark.Result result=TypingBenchmark.score("안녕 😀","안녕 😄",5000);
        assertEquals(1,result.characterErrors); assertEquals(25,result.characterErrorRate,.001);
    }
    @Test public void benchmarkDoesNotHideExtraWordsOrEmptyInput() {
        assertEquals(100,TypingBenchmark.score("hello","",1000).wordErrorRate,.001);
        assertEquals(200,TypingBenchmark.score("hello","hello there friend",1000).wordErrorRate,.001);
    }
}
