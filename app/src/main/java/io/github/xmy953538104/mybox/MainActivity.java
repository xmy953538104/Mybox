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
import android.widget.Switch;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.xmy953538104.mybox.engine.Engine;
import io.github.xmy953538104.mybox.engine.EngineDiagnostics;
import io.github.xmy953538104.mybox.engine.BundledMicrog;
import io.github.xmy953538104.mybox.engine.GoogleComponents;
import io.github.xmy953538104.mybox.engine.packages.InstalledPackage;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLING = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String operationMessage;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<Button> importActions = new ArrayList<>();
    private final Runnable observeInstall = new Runnable() {
        @Override public void run() {
            if (INSTALLING.get()) { status.setText(operationMessage); ui.postDelayed(this,250); }
            else refresh();
        }
    };
    private TextView status, googleStatus;
    private int refreshVersion;
    private LinearLayout packages;
    private ScrollView scroll;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("Mybox · Mylite");
        title.setTextSize(24);
        body.addView(title);
        TextView description = new TextView(this);
        description.setText("独立重建的应用容器。导入可信 APK；分包应用请同时选择 base 与全部 splits。Google 登录、下载和推送仍需逐项验收。");
        body.addView(description);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        body.addView(status);
        importActions.add(button(body, "导入 APK", () -> pickSource(1,true)));
        googleStatus = new TextView(this);
        body.addView(googleStatus);
        button(body, "刷新应用", this::refresh);
        try {
            String[] bundled = getAssets().list("mybox");
            if (bundled != null && java.util.Arrays.asList(bundled).contains("trusted-microg.properties")) {
                importActions.add(button(body, "准备 Google 服务与商店", () -> install("正在校验和安装 Google 组件…", () -> {
                    BundledMicrog.install();
                    Engine.get().prepareGoogleServices();
                    GoogleComponents.installStore();
                    return "microG 与 Play 商店已安装。请打开 Google Play 商店继续登录。";
                })));
                importActions.add(button(body, "选择 Google Play APK（含全部分包）", () -> pickSource(2,true)));
                importActions.add(button(body, "从已有 GBox 安装包导入 Play", () -> pickSource(3,false)));
            }
        } catch (IOException failure) { showFailure(failure); }
        Switch debug = new Switch(this);
        debug.setText("调试日志（最多约 1.2 MiB，默认关闭）");
        debug.setChecked(EngineDiagnostics.enabled());
        body.addView(debug);
        debug.setOnCheckedChangeListener((view, checked) -> {
            if (!debug.isEnabled()) return;
            debug.setEnabled(false);
            worker.execute(() -> {
                try {
                    EngineDiagnostics.setEnabled(checked);
                    if (checked) Engine.get().captureDiagnostics();
                    operationMessage = checked ? "调试日志已开启，复现问题后可导出" : "调试日志已关闭，已有记录保留";
                } catch (IOException | RuntimeException failure) { EngineDiagnostics.failure("ui", "debug_toggle", 0, failure); operationMessage = "调试设置未完整完成：" + failure.getClass().getSimpleName(); }
                ui.post(() -> { if (!isDestroyed()) { debug.setChecked(EngineDiagnostics.enabled()); debug.setEnabled(true); refresh(); } });
            });
        });
        button(body, "导出诊断日志", () -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "Mybox-diagnostics.zip"), 4);
            } catch (android.content.ActivityNotFoundException failure) { showFailure(failure); }
        });
        button(body, "清除诊断日志", () -> worker.execute(() -> {
            try { EngineDiagnostics.clear(); operationMessage = "诊断日志已清除，账号与应用数据保留"; }
            catch (IOException failure) { operationMessage = "日志清除失败：" + failure.getClass().getSimpleName(); }
            ui.post(() -> { if (!isDestroyed()) refresh(); });
        }));
        packages = new LinearLayout(this);
        packages.setOrientation(LinearLayout.VERTICAL);
        body.addView(packages);
        refresh();
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    private void pickSource(int request, boolean multiple) {
        if (INSTALLING.get()) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multiple),request);
        } catch (android.content.ActivityNotFoundException failure) {
            operationMessage="系统文件选择器不可用，请启用系统“文件”应用后重试"; refresh();
        }
    }
    private interface Install { String run() throws Exception; }
    private void install(String message, Install action) {
        if (!INSTALLING.compareAndSet(false,true)) return;
        for(Button actionButton : importActions) actionButton.setEnabled(false);
        scroll.smoothScrollTo(0,0);
        operationMessage=message; status.setText(message); ui.removeCallbacks(observeInstall); ui.post(observeInstall);
        worker.execute(() -> {
            try { operationMessage=action.run(); }
            catch (Exception | LinkageError failure) {
                operationMessage="操作未完成：" + failure.getMessage();
                android.util.Log.w("MyliteSetup","Installation did not complete",failure);
            } finally {
                INSTALLING.set(false);
                ui.post(() -> { if(!isDestroyed()) refresh(); });
            }
        });
    }
    @Override protected void onResume() { super.onResume(); ui.removeCallbacks(observeInstall); ui.post(observeInstall); }
    @Override protected void onPause() { ui.removeCallbacks(observeInstall); super.onPause(); }

    private void refresh() {
        try {
            ((MyboxApplication) getApplication()).requireEngine();
            List<InstalledPackage> installed = Engine.get().packages().list();
            packages.removeAllViews();
            boolean microg=false,store=false;
            for (InstalledPackage record : installed) {
                microg |= "com.google.android.gms".equals(record.packageName);
                store |= "com.android.vending".equals(record.packageName);
            }
            int version = ++refreshVersion;
            String baseStatus = "Google 服务（microG）：" + (microg ? "已安装" : "未安装")
                    + "\nGoogle Play 商店：" + (store ? "已安装，可从下方打开" : "未安装，请选择商店来源")
                    + "\n账户登录及云消息请在 microG 设置中检查；安装不代表已经登录或启用推送。";
            googleStatus.setText(baseStatus);
            if (microg) worker.execute(() -> {
                String recovery;
                try { recovery = Engine.get().googleServiceStatus(); }
                catch (RuntimeException failure) { recovery = "Google 服务状态暂不可用：" + failure.getClass().getSimpleName(); }
                String message = recovery;
                ui.post(() -> {
                    if (!isDestroyed() && refreshVersion == version)
                        googleStatus.setText(baseStatus + (message.isEmpty() ? "" : "\n" + message));
                });
            });
            for(Button action : importActions) action.setEnabled(!INSTALLING.get());
            for (InstalledPackage record : installed) {
                String label = "com.google.android.gms".equals(record.packageName) ? "microG 设置"
                        : "com.android.vending".equals(record.packageName) ? "Google Play 商店" : record.packageName;
                button(packages, label, () -> worker.execute(() -> {
                    try { Engine.get().launch(record.packageName); }
                    catch (Throwable failure) { runOnUiThread(() -> showFailure(failure)); }
                }));
            }
            status.setText(operationMessage == null ? "已安装 " + installed.size() + " 个应用" : operationMessage);
        } catch (Throwable failure) { showFailure(failure); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 4) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri destination = data.getData();
                worker.execute(() -> {
                    try {
                        try { Engine.get().captureDiagnostics(); }
                        catch (RuntimeException failure) { EngineDiagnostics.failure("ui", "snapshot", 0, failure); }
                        try (java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                            if (output == null) throw new IOException("系统未提供导出文件");
                            EngineDiagnostics.export(this, output);
                        }
                        operationMessage = "诊断日志已导出，不含账号、令牌或原始网络正文";
                    } catch (IOException | RuntimeException failure) {
                        operationMessage = "日志导出失败：" + failure.getClass().getSimpleName();
                    }
                    ui.post(() -> { if (!isDestroyed()) refresh(); });
                });
            }
            return;
        }
        if ((request < 1 || request > 3) || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clips = data.getClipData();
        if (clips != null) {
            if (clips.getItemCount()>256) { operationMessage="每次最多选择 256 个安装包"; refresh(); return; }
            for (int i = 0; i < clips.getItemCount(); i++) uris.add(clips.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        if (request==3 && uris.size()!=1) { operationMessage="请选择一个包含 Play 的 GBox 安装包"; refresh(); return; }
        install("正在读取、验证安装包…", () -> {
            List<File> temporary = new ArrayList<>();
            try {
                long remaining=request==1 ? 2L*1024*1024*1024 : 512L*1024*1024;
                for (Uri uri : uris) {
                    File file = File.createTempFile("engine-import-", ".apk", getCacheDir());
                    temporary.add(file);
                    try (InputStream input = getContentResolver().openInputStream(uri)) { copyApk(input,file,remaining); }
                    remaining-=file.length();
                }
                InstalledPackage installed;
                if(request==1) installed=Engine.get().install(temporary);
                else {
                    BundledMicrog.install();
                    installed=request==2 ? GoogleComponents.importStore(temporary) : GoogleComponents.importReferenceStore(temporary.get(0));
                    Engine.get().prepareGoogleServices();
                }
                return request==1 ? "已安装：" + installed.packageName : "microG 与 Play 商店已安装。请打开 Google Play 商店继续登录。";
            } finally {
                for(File file : temporary) if(!file.delete() && file.exists()) android.util.Log.w("MyliteSetup","Temporary import cleanup deferred");
            }
        });
    }

    static void copyApk(InputStream input, File file) throws IOException {
        copyApk(input,file,2L*1024*1024*1024);
    }
    private static void copyApk(InputStream input, File file, long remaining) throws IOException {
        if (input == null) throw new IOException("无法读取文件");
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if(count==0) { int one=input.read(); if(one<0) break; buffer[0]=(byte)one; count=1; }
                if(count>remaining-total) throw new IOException("安装包集合超出大小限制");
                total += count;
                output.write(buffer, 0, count);
            }
            if (total == 0) throw new IOException("文件为空");
            output.getFD().sync();
        }
    }

    private void showFailure(Throwable failure) {
        EngineDiagnostics.failure("ui", "operation", 0, failure);
        while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        status.setText("操作失败：" + failure.getClass().getSimpleName() + "：" + failure.getMessage());
    }
    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }
}
