package com.gazlaws.codeboard;

import static org.junit.Assert.*;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class TypingBenchmarkActivityTest {
    @Test public void practiceDisablesLearningAndScoresTheActualEnteredText() {
        TypingBenchmarkActivity activity=Robolectric.buildActivity(TypingBenchmarkActivity.class).setup().get();
        ViewGroup root=activity.findViewById(android.R.id.content);
        EditText input=findInput(root);
        assertNotNull(input);
        assertNotEquals(0,input.getImeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setText("The sitter watched the children during the strike.");
        assertTrue(findButton(root,"Score").performClick());
        assertTrue(containsText(root,"Character error rate: 0.0%"));
        assertTrue(findButton(root,"English / 한국어").performClick());
        assertTrue(containsText(root,"내일 오후에"));
        assertEquals("",input.getText().toString());
    }
    private static EditText findInput(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            EditText found=findInput(((ViewGroup)view).getChildAt(i)); if(found!=null)return found;
        }
        return null;
    }
    private static Button findButton(View view,String label) {
        if(view instanceof Button && ((Button)view).getText().toString().equals(label))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=findButton(((ViewGroup)view).getChildAt(i),label); if(found!=null)return found;
        }
        return null;
    }
    private static boolean containsText(View view,String text) {
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(text))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            if(containsText(((ViewGroup)view).getChildAt(i),text))return true;
        return false;
    }
}
