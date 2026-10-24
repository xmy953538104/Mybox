package io.github.xmy953538104.mybox.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public final class SecondActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Guest second Activity: " + getPackageName());
        setContentView(text);
    }
}
