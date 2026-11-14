package com.gazlaws.codeboard.gesture;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

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

    private static EnglishContextModel realModel() throws Exception {
        File file = new File("src/main/assets/swipe/en_context.tsv");
        if (!file.isFile()) file = new File("app/src/main/assets/swipe/en_context.tsv");
        try (FileInputStream input = new FileInputStream(file)) {
            return EnglishContextModel.load(input);
        }
    }
}
