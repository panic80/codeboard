package com.gazlaws.codeboard.gesture;

import static org.junit.Assert.*;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.Collections;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class AdaptiveTypingModelTest {
    private Context context;
    private AdaptiveTypingModel model;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); model = new AdaptiveTypingModel(context); model.clear(); }
    @Test public void ordinaryUnknownWordsNeedThreeCommitsAndPersistAcrossInstances() {
        model.observe("", "zorbax", false); model.observe("", "zorbax", false);
        assertTrue(model.entries(false).isEmpty());
        model.observe("", "zorbax", false);
        assertEquals("zorbax", new AdaptiveTypingModel(context).entries(false).get(0).word);
    }
    @Test public void explicitChoiceLearnsAndUndoSuppressesTheSpecificReplacement() {
        model.choose("a ", "sitter", "strike"); model.choose("a ", "sitter", "strike");
        assertEquals(2, model.choices("Sitter", "Strike"));
        model.reject("sitter", "strike");
        assertTrue(model.rejected("sitter", "strike"));
        assertFalse(model.rejected("sitter", "site"));
        assertEquals(0, model.choices("sitter", "strike"));
    }
    @Test public void fourWordContextDistinguishesTheSameTwoWordEnding() {
        for (int i=0;i<3;i++) {
            model.observe("we planned to visit ", "seoul", false);
            model.observe("they decided to visit ", "toronto", false);
        }
        assertEquals("seoul", model.predict("we planned to visit ", 1).get(0));
        assertEquals("toronto", model.predict("they decided to visit ", 1).get(0));
    }
    @Test public void koreanPhrasesLearnWithoutEnteringEnglishDictionary() {
        model.observe("내일 아침에 ", "만나요", true); model.observe("내일 아침에 ", "만나요", true);
        assertEquals("만나요", model.predict("내일 아침에 ", 1).get(0));
        assertTrue(model.entries(false).isEmpty()); assertEquals(1, model.entries(true).size());
    }
    @Test public void emailUrlsIdentifiersAndNumbersAreNotLearned() {
        for(String word:Arrays.asList("person@example.com", "https://example.com", "item_123", "123456", "hello world", "myVariable", "CodeBoard"))
            model.observe("", word, true);
        assertTrue(model.entries(false).isEmpty());
    }
    @Test public void resetInvalidatesAnAlreadyLoadedInstance() {
        model.observe("", "zorbax", true);
        AdaptiveTypingModel second = new AdaptiveTypingModel(context);
        assertEquals(1, second.entries(false).size()); int old=second.lexiconVersion();
        model.clear(); assertTrue(second.entries(false).isEmpty()); assertNotEquals(old, second.lexiconVersion());
    }
    @Test public void boundariesDoNotCarryContextAcrossSentencePunctuation() {
        assertEquals(Collections.singletonList("hello"), AdaptiveTypingModel.contextWords("old sentence. hello "));
    }
    @Test public void preferenceCannotGrowWithoutBound() {
        for(int i=0;i<100;i++) model.observe("this is a test ", "word", false);
        assertTrue(model.preference("this is a test ", "word") <= .65);
    }
    @Test public void authoredKoreanAndLongEnglishContextsPredict() throws Exception {
        String data="내일 아침에 다시 만나요\nplease let me know tomorrow\n";
        PhraseContextModel phrases=new PhraseContextModel(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)),null);
        assertEquals("만나요",phrases.predict("내일 아침에 다시 ",1).get(0));
        assertEquals("tomorrow",phrases.predict("please let me know ",1).get(0));
        assertTrue(phrases.score("내일 아침에 다시 ","만나요").trigramCount > 0);
    }
    @Test public void wordBoundaryCandidatesRequirePhraseSupportAndPreserveIdentifiers() throws Exception {
        BoundarySuggestions boundaries=new BoundarySuggestions(Arrays.asList(new SwipeDecoder.Entry("see",1000),new SwipeDecoder.Entry("you",1000)));
        PhraseContextModel phrases=new PhraseContextModel(new ByteArrayInputStream("see you tomorrow".getBytes(StandardCharsets.UTF_8)),null);
        assertEquals(Collections.singletonList("see you"),boundaries.split("seeyou",phrases));
        assertTrue(boundaries.split("seeYou",phrases).isEmpty());
        assertTrue(boundaries.split("seeyou",null).isEmpty());
    }
    @Test public void rejectionPreventsAutomaticReplacementAfterRestart() {
        model.reject("teh","the");
        TypingCorrector.Result initial=new TypingCorrector(Arrays.asList(new SwipeDecoder.Entry("the",100000)),false).suggest("teh",5);
        TypingCorrector.Result result=PersonalizedSuggestions.apply(initial,"teh","",new AdaptiveTypingModel(context),null,Collections.emptyList(),Collections.emptyList(),5);
        assertNull(result.correction); assertNull(result.automaticSuggestion);
    }
    @Test public void punctuationAndNewlinesEndLearnedContext() {
        assertEquals(Collections.singletonList("hello"),AdaptiveTypingModel.contextWords("old paragraph\nhello "));
    }
    @Test public void neutralTouchEvidenceKeepsTranspositionCorrections() {
        TouchModel touch=new TouchModel(context); touch.clear();
        java.util.List<SwipeDecoder.KeyPosition> keys=Arrays.asList(new SwipeDecoder.KeyPosition('t',0,0,100,100),
                new SwipeDecoder.KeyPosition('e',100,0,100,100),new SwipeDecoder.KeyPosition('h',200,0,100,100));
        java.util.List<TouchModel.Sample> samples=Arrays.asList(new TouchModel.Sample('t',0,0,"P",keys),
                new TouchModel.Sample('e',100,0,"P",keys),new TouchModel.Sample('h',200,0,"P",keys));
        TypingCorrector.Result initial=new TypingCorrector(Arrays.asList(new SwipeDecoder.Entry("the",100000),
                new SwipeDecoder.Entry("eh",100)),false).suggest("teh",5);
        TypingCorrector.Result result=PersonalizedSuggestions.apply(initial,"teh","",model,touch,samples,Collections.emptyList(),5);
        assertEquals(initial.correction,result.correction); assertEquals(initial.candidates,result.candidates);
    }
    @Test public void validWordPreferencesRequireTheSameContextForAutomaticReplacement() {
        model.choose("it is ", "bot", "not"); model.choose("it is ", "bot", "not");
        assertEquals(2, model.contextualChoices("it is ","bot","not"));
        assertEquals(0, model.contextualChoices("a helpful ","bot","not"));
    }

    @Test public void commonWordsUpdatePreferencesWithoutRebuildingTheLexicon() {
        int before=model.lexiconVersion();
        for(int i=0;i<10;i++)model.observe("see you ","soon",false,true);
        assertEquals(before,model.lexiconVersion());
        assertTrue(model.preference("see you ","soon")>0);
    }

    @Test public void touchCalibrationIsBoundedAndSeparatedByGeometry() {
        TouchModel touch=new TouchModel(context); touch.clear();
        java.util.List<SwipeDecoder.KeyPosition> keys=Collections.singletonList(new SwipeDecoder.KeyPosition('a',0,0,100,100));
        java.util.List<TouchModel.Sample> portrait=Collections.singletonList(new TouchModel.Sample('a',25,10,"P",keys));
        java.util.List<TouchModel.Sample> landscape=Collections.singletonList(new TouchModel.Sample('a',25,10,"L",keys));
        double before=touch.cost(portrait,"a");
        for(int i=0;i<10;i++)touch.learn(portrait,"a");
        assertTrue(touch.cost(portrait,"a") < before);
        assertEquals(before,touch.cost(landscape,"a"),.0001);
        touch.clear(); assertEquals(before,touch.cost(portrait,"a"),.0001);
    }
}
