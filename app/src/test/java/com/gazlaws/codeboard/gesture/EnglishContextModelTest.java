package com.gazlaws.codeboard.gesture;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EnglishContextModelTest {
    @Test public void parsesCountsAndIgnoresInvalidRows() throws Exception {
        String rows = "# fixture\n"
                + "is\tnot\t12\nIS\tNOT\t8\nthis\tis\tnot\t5\n"
                + "can't\tbe\t42\n"
                + "is\tbad\tNaN\nis\tbad\t-1\nis\tbad!\t100\n";
        EnglishContextModel model = EnglishContextModel.load(new ByteArrayInputStream(
                rows.getBytes(StandardCharsets.UTF_8)));
        TypingCorrector.ContextEvidence score = model.score("This IS ", "NOT");
        assertEquals(20, score.bigramCount);
        assertEquals(5, score.trigramCount);
        assertEquals(42, model.score("can’t ", "BE").bigramCount);
        assertEquals(0, model.score("is ", "bad").bigramCount);
        assertEquals(0, model.score("is ", "not!").bigramCount);
    }

    @Test public void punctuationAndLineBreaksDoNotCrossContextBoundaries() throws Exception {
        String rows = "is\tnot\t12\nthis\tis\tnot\t5\n";
        EnglishContextModel model = EnglishContextModel.load(new ByteArrayInputStream(
                rows.getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, model.score("is. ", "not").bigramCount);
        assertEquals(0, model.score("is\n", "not").bigramCount);
        assertEquals(0, model.score("is2 ", "not").bigramCount);
        assertEquals(0, model.score("name@example.com ", "not").bigramCount);
        assertEquals(12, model.score("This. is ", "not").bigramCount);
        assertEquals(0, model.score("This. is ", "not").trigramCount);
        assertEquals(12, model.score("This\nis ", "not").bigramCount);
        assertEquals(0, model.score("This\nis ", "not").trigramCount);
    }

    @Test public void preparedLookupsMatchDirectLookups() throws Exception {
        EnglishContextModel model = realModel();
        EnglishContextModel.PreparedContext context = model.prepare("This is ");
        for (String word : new String[] {"not", "bot", "a", "can't", "unknown"}) {
            assertEquals(model.score("This is ", word).bigramCount,
                    context.score(word).bigramCount);
            assertEquals(model.score("This is ", word).trigramCount,
                    context.score(word).trigramCount);
        }
    }

    @Test public void bundledCorpusProvidesEvidenceAcrossDifferentPhrases() throws Exception {
        EnglishContextModel model = realModel();
        assertEquals(10282, model.score("is ", "not").bigramCount);
        assertEquals(0, model.score("is ", "bot").bigramCount);
        assertTrue(model.score("you ", "are").bigramCount > 1000);
        assertTrue(model.score("at ", "home").bigramCount > 100);
        assertTrue(model.score("I ", "want").bigramCount > 100);
        assertTrue(model.score("I do ", "not").trigramCount > 100);
        assertEquals(0, model.score("the ", "not").bigramCount);
        assertEquals(0, model.score("chat ", "not").bigramCount);
        assertTrue(model.bigramOmittedUpperBound() > 0);
        assertTrue(model.trigramOmittedUpperBound() > 0);
    }

    @Test public void predictionsPreferObservedTrigramsThenFillFromRankedBigrams() throws Exception {
        String rows = "you\tcan\t90\nyou\twill\t80\nyou\tcan\t10\n"
                + "you\tfor\t70\nyou\talways\t70\nyou\tnever\t1\n"
                + "thank\tyou\tfor\t3\nthank\tyou\tcan\t2\n";
        EnglishContextModel model = EnglishContextModel.load(new ByteArrayInputStream(
                rows.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Arrays.asList("for", "can", "will", "always", "never"),
                model.predict("Thank YOU ", 100));
        assertEquals(Arrays.asList("can", "will", "always"), model.predict("you ", 3));
        assertEquals(Collections.emptyList(), model.predict("you ", 0));
        List<String> immutable = model.predict("you ", 5);
        try {
            immutable.add("anything");
            throw new AssertionError("Predictions must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test public void predictionDoesNotBridgePunctuationOrLineBreaks() throws Exception {
        String rows = "you\tcan\t9\nthank\tyou\tfor\t3\n";
        EnglishContextModel model = EnglishContextModel.load(new ByteArrayInputStream(
                rows.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Collections.emptyList(), model.predict("you. ", 5));
        assertEquals(Collections.emptyList(), model.predict("you\n", 5));
        assertEquals(Collections.singletonList("can"), model.predict("thank. you ", 5));
        assertEquals(Collections.singletonList("can"), model.predict("thank\nyou ", 5));
    }

    @Test public void bundledCorpusPredictsACommonTrigram() throws Exception {
        assertEquals("for", realModel().predict("thank you ", 5).get(0));
    }

    @Test public void firstPersonPredictionsUseCanonicalCapitalization() throws Exception {
        String rows = "and\ti\t10\nand\ti'm\t9\nand\tiraq\t8\n";
        EnglishContextModel model = EnglishContextModel.load(new ByteArrayInputStream(
                rows.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Arrays.asList("I", "I'm", "iraq"), model.predict("and ", 5));
    }

    private static EnglishContextModel realModel() throws Exception {
        File file = new File("src/main/assets/swipe/en_context.tsv");
        if (!file.isFile()) file = new File("app/src/main/assets/swipe/en_context.tsv");
        try (FileInputStream input = new FileInputStream(file)) {
            return EnglishContextModel.load(input);
        }
    }
}
