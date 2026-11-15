package com.gazlaws.codeboard;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.gazlaws.codeboard.input.TypingBenchmark;
import java.util.Locale;

/** A repeatable on-device exercise usable with Codeboard or another selected keyboard. */
// The no-learning flag is an inlined bit, also honored by Codeboard before API 26.
@android.annotation.SuppressLint({"SetTextI18n", "InlinedApi"})
public final class TypingBenchmarkActivity extends Activity {
    private static final String[] PHRASES = {
        "The sitter watched the children during the strike.",
        "This shirt is shorter than the third one.",
        "Please bring the blue notebook after lunch.",
        "We should leave before the traffic gets worse.",
        "The package arrived earlier than expected.",
        "내일 오후에 도서관에서 책을 빌릴 거예요.",
        "이번 주말에는 가족과 함께 여행을 가요.",
        "잠깐 기다리시면 제가 바로 확인하겠습니다."
    };
    private int index;
    private long started = -1;
    private boolean submitted;
    private int removed;
    private EditText input;
    private TextView prompt, result;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("Typing accuracy test");
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(16*getResources().getDisplayMetrics().density); root.setPadding(padding,padding,padding,padding);
        TextView instructions=new TextView(this);
        instructions.setText("Copy the sentence using taps or swipes. Correct it as you normally would, then tap Score. Use the same sentences with another keyboard to compare. Learning is paused in this test.");
        root.addView(instructions);
        prompt=new TextView(this); prompt.setTextSize(20); prompt.setPadding(0,padding,0,padding); root.addView(prompt);
        input=new EditText(this); input.setHint("Type the sentence here"); input.setMinLines(2);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        root.addView(input);
        result=new TextView(this); root.addView(result);
        Button score=new Button(this); score.setText("Score"); score.setOnClickListener(v -> score()); root.addView(score);
        Button next=new Button(this); next.setText("Next sentence / 다음 문장"); next.setOnClickListener(v -> { index=(index+1)%PHRASES.length; reset(); }); root.addView(next);
        Button language=new Button(this); language.setText("English / 한국어"); language.setOnClickListener(v -> { index=index<5?5:0; reset(); }); root.addView(language);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { if(started>=0&&!submitted)removed+=Math.max(0,count-after); }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { if(started<0&&s.length()>0)started=SystemClock.elapsedRealtime(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root); setContentView(scroll);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        reset();
    }
    private void reset() { submitted=true; input.setText(""); submitted=false; started=-1; removed=0; prompt.setText(PHRASES[index]); result.setText(""); input.requestFocus(); }
    private void score() {
        if(started<0||submitted)return;
        TypingBenchmark.Result measured=TypingBenchmark.score(PHRASES[index],input.getText().toString(),SystemClock.elapsedRealtime()-started);
        String summary=String.format(Locale.getDefault(),"Character error rate: %.1f%%\nWord error rate: %.1f%%\nSpeed: %.1f WPM\nCharacters removed while editing: %d",measured.characterErrorRate,measured.wordErrorRate,measured.wordsPerMinute,removed);
        result.setText(summary); submitted=true;
        getSharedPreferences("typing_benchmark",MODE_PRIVATE).edit().putString("last_result",summary).apply();
    }
}
