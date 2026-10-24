package io.github.xmy953538104.mybox;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.BundledMicrog;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private LinearLayout packages;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("Mybox · VLite 重建验证");
        title.setTextSize(24);
        body.addView(title);
        TextView description = new TextView(this);
        description.setText("独立重建的应用容器。导入可信 APK；分包应用请同时选择 base 与全部 splits。Google 登录、下载和推送仍需逐项验收。");
        body.addView(description);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        body.addView(status);
        button(body, "导入 APK", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(pick, 1);
        });
        button(body, "刷新应用", this::refresh);
        try {
            String[] bundled = getAssets().list("mybox");
            if (bundled != null && java.util.Arrays.asList(bundled).contains("trusted-microg.properties")) {
                button(body, "安装内置 microG", () -> worker.execute(() -> {
                    try {
                        InstalledPackage installed = BundledMicrog.install();
                        runOnUiThread(() -> { refresh(); status.setText("已安装：" + installed.packageName); });
                    } catch (Throwable failure) { runOnUiThread(() -> showFailure(failure)); }
                }));
            }
        } catch (IOException failure) { showFailure(failure); }
        packages = new LinearLayout(this);
        packages.setOrientation(LinearLayout.VERTICAL);
        body.addView(packages);
        refresh();
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }

    private void refresh() {
        try {
            ((MyboxApplication) getApplication()).requireEngine();
            List<InstalledPackage> installed = Engine.get().packages().list();
            packages.removeAllViews();
            for (InstalledPackage record : installed) {
                button(packages, record.packageName, () -> worker.execute(() -> {
                    try { Engine.get().launch(record.packageName); }
                    catch (Throwable failure) { runOnUiThread(() -> showFailure(failure)); }
                }));
            }
            status.setText("已安装 " + installed.size() + " 个应用");
        } catch (Throwable failure) { showFailure(failure); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clips = data.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) uris.add(clips.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        status.setText("正在验证 APK 并安装…");
        worker.execute(() -> {
            List<File> temporary = new ArrayList<>();
            try {
                for (Uri uri : uris) {
                    File file = File.createTempFile("engine-import-", ".apk", getCacheDir());
                    temporary.add(file);
                    try (InputStream input = getContentResolver().openInputStream(uri)) { copyApk(input, file); }
                }
                InstalledPackage installed = Engine.get().install(temporary);
                runOnUiThread(() -> { refresh(); status.setText("已安装：" + installed.packageName); });
            } catch (Throwable failure) { runOnUiThread(() -> showFailure(failure)); }
            finally { for (File file : temporary) file.delete(); }
        });
    }

    static void copyApk(InputStream input, File file) throws IOException {
        if (input == null) throw new IOException("无法读取文件");
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 2L * 1024 * 1024 * 1024) throw new IOException("APK 超出 2 GiB 限制");
                output.write(buffer, 0, count);
            }
            if (total == 0) throw new IOException("文件为空");
            output.getFD().sync();
        }
    }

    private void showFailure(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        status.setText("操作失败：" + failure.getClass().getSimpleName() + "：" + failure.getMessage());
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
