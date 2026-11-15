package me.rerere.rikkahub.data.orbis.agentwork;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;

/** Synthetic separate app. Java keeps the test APK independent of target Kotlin runtime. */
public class PhoneFixtureActivity extends Activity {
    private int count = 0;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra("dense", false)) { densePage(); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView result = new TextView(this); result.setText("计数 0"); result.setTextSize(24f);
        box.addView(result);
        // QQ-like message content may sit under a container excluded from the default tree.
        LinearLayout bubble = new LinearLayout(this);
        bubble.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView message = new TextView(this); message.setText("已发送的合成消息");
        bubble.addView(message); box.addView(bubble);
        Button button = new Button(this); button.setText("增加一次");
        button.setOnClickListener(v -> { count++; result.setText("计数 " + count); }); box.addView(button);
        EditText input = new EditText(this); input.setContentDescription("测试输入"); input.setInputType(InputType.TYPE_CLASS_TEXT); box.addView(input);
        EditText password = new EditText(this); password.setContentDescription("测试密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setText("fixture-secret"); box.addView(password);
        for(int n = 0; n < 40; n++) { TextView row = new TextView(this); row.setText("滚动条目 " + n); row.setTextSize(24f); box.addView(row); }
        ScrollView scroll = new ScrollView(this); scroll.addView(box); setContentView(scroll);
    }

    private void densePage() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this); title.setText("合成群聊"); root.addView(title);
        FrameLayout history = new FrameLayout(this);
        // Visible layout wrappers plus many rich bubbles precede the composer in tree order.
        for (int i = 0; i < 450; i++) history.addView(new FrameLayout(this), new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < 100; i++) {
            TextView bubble = new TextView(this);
            bubble.setText("合成消息 " + i + "：" + new String(new char[400]).replace('\0', '文'));
            history.addView(bubble, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(history, new LinearLayout.LayoutParams(-1, 0, 1f));
        FrameLayout composer = new FrameLayout(this); FrameLayout current = composer;
        for (int i = 0; i < 30; i++) { FrameLayout nested = new FrameLayout(this); current.addView(nested); current = nested; }
        EditText input = new EditText(this); input.setContentDescription("深层输入框");
        current.addView(input, new FrameLayout.LayoutParams(-1, -1));
        root.addView(composer, new LinearLayout.LayoutParams(-1, 140));
        Button send = new Button(this); send.setText("合成发送");
        send.setOnClickListener(v -> title.setText("合成已发送")); root.addView(send);
        setContentView(root);
    }
}
