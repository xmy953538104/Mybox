package io.github.xmy953538104.mybox.fixture;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Ordinary Android windows; the controller must really touch and dismiss each one. */
public final class WindowProbe {
    public static void run(Activity activity) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        Button popup = new Button(activity); popup.setText("Open Mylite popup"); body.addView(popup);
        Button dialog = new Button(activity); dialog.setText("Open Mylite dialog"); body.addView(dialog);
        Button toast = new Button(activity); toast.setText("Show Mylite toast"); body.addView(toast);
        // Android's custom-toast TN weakly references the view and callback list.
        Toast[] pendingToast = new Toast[1];
        popup.setOnClickListener(view -> {
            Button dismiss = new Button(activity); dismiss.setText("Dismiss Mylite popup");
            int width = Math.round(260 * activity.getResources().getDisplayMetrics().density);
            int height = Math.round(100 * activity.getResources().getDisplayMetrics().density);
            PopupWindow window = new PopupWindow(dismiss, width, height, true);
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            window.setOutsideTouchable(true);
            window.setOnDismissListener(() -> publish(activity, "popup-dismissed"));
            dismiss.setOnClickListener(clicked -> { publish(activity, "popup-clicked"); window.dismiss(); });
            window.showAtLocation(body, Gravity.CENTER, 0, 0);
        });
        dialog.setOnClickListener(view -> {
            AlertDialog window = new AlertDialog.Builder(activity).setTitle("Mylite dialog")
                    .setMessage("This is a real guest dialog window")
                    .setPositiveButton("Close Mylite dialog", (closed, which) -> publish(activity, "dialog-clicked")).create();
            window.setOnDismissListener(closed -> publish(activity, "dialog-dismissed"));
            window.show();
        });
        toast.setOnClickListener(view -> {
            if (pendingToast[0] != null) pendingToast[0].cancel();
            Toast message = Toast.makeText(activity, "Mylite window toast", Toast.LENGTH_LONG);
            pendingToast[0] = message;
            android.view.View toastView = message.getView();
            if (toastView == null) throw new IllegalStateException("The legacy-target fixture has no custom Toast view");
            toastView.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(android.view.View attached) { publish(activity, "toast-attached"); }
                @Override public void onViewDetachedFromWindow(android.view.View detached) { publish(activity, "toast-detached"); }
            });
            if (Build.VERSION.SDK_INT >= 30) message.addCallback(new Toast.Callback() {
                @Override public void onToastShown() { publish(activity, "toast-shown"); }
                @Override public void onToastHidden() {
                    publish(activity, "toast-hidden");
                    if (pendingToast[0] == message) pendingToast[0] = null;
                }
            });
            message.show();
        });
        activity.setContentView(body);
        publish(activity, "ready");
    }

    private static void publish(Activity activity, String phase) {
        try {
            File result = new File(activity.getFilesDir(), "window-" + phase), pending = new File(activity.getFilesDir(), "window-" + phase + ".tmp");
            Files.write(pending.toPath(), "true".getBytes(StandardCharsets.UTF_8));
            Files.move(pending.toPath(), result.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception failure) { throw new IllegalStateException("Cannot publish window result", failure); }
    }
    private WindowProbe() { }
}
