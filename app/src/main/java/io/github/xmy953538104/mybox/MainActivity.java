package io.github.xmy953538104.mybox;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
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

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;

public final class MainActivity extends Activity {
    private static final int IMPORT_APKS = 1;
    private static final int VIRTUAL_USER = 0;
    private static final long MAX_APK_BYTES = 2L * 1024 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private LinearLayout packages;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("Mybox · 开发预览");
        title.setTextSize(24);
        content.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("免 Root · 无 HMS · 无 Xposed\n应用安装在 Mybox 容器中。Google Play 需导入原版 APK；首次登录由 Google 登录页完成。当前版本尚在兼容性验证中。");
        content.addView(explanation);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        content.addView(status);
        addButton(content, "1. 安装内置 microG", view -> installBundledMicroG());
        addButton(content, "2. 导入 Google Play 或应用 APK", view -> chooseApks());
        addButton(content, "打开 microG 设置", view -> launch("com.google.android.gms"));
        addButton(content, "打开 Google Play", view -> launch("com.android.vending"));
        addButton(content, "授予位置与通知权限", view -> requestHostPermissions());
        addButton(content, "开启后台连接通知", view -> {
            Intent intent = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        });
        addButton(content, "停止后台连接通知", view -> stopService(new Intent(this, KeepAliveService.class)));
        addButton(content, "刷新应用列表", view -> refresh());
        packages = new LinearLayout(this);
        packages.setOrientation(LinearLayout.VERTICAL);
        content.addView(packages);
        refresh();
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        parent.addView(button);
    }

    private void chooseApks() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, IMPORT_APKS);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_APKS || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        status.setText("正在验证并导入 APK…");
        worker.execute(() -> {
            List<File> staged = new ArrayList<>();
            try {
                ((MyboxApplication) getApplication()).requireEngine();
                for (Uri uri : uris) {
                    File file = File.createTempFile("import-", ".apk", getCacheDir());
                    staged.add(file);
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        copyApk(input, file);
                    }
                }
                String[] paths = new String[staged.size()];
                for (int i = 0; i < paths.length; i++) paths[i] = staged.get(i).getAbsolutePath();
                InstallResult result = BlackBoxCore.getBPackageManager().installPackageSetAsUser(
                        paths, InstallOption.installByStorage(), VIRTUAL_USER, getPackageName());
                showInstallResult(result);
            } catch (Throwable failure) {
                showFailure(failure);
            } finally {
                for (File file : staged) file.delete();
            }
        });
    }

    private void installBundledMicroG() {
        status.setText("正在验证并安装 microG…");
        worker.execute(() -> {
            File file = new File(getCacheDir(), "microg-nohms.apk");
            try {
                ((MyboxApplication) getApplication()).requireEngine();
                try (InputStream input = getAssets().open("mybox/microg-nohms.apk")) {
                    copyApk(input, file);
                }
                showInstallResult(BlackBoxCore.getBPackageManager().installPackageSetAsUser(
                        new String[]{file.getAbsolutePath()}, InstallOption.installByStorage(),
                        VIRTUAL_USER, getPackageName()));
            } catch (Throwable failure) {
                showFailure(failure);
            } finally {
                file.delete();
            }
        });
    }

    static void copyApk(InputStream input, File target) throws IOException {
        if (input == null) throw new IOException("无法读取所选文件");
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_APK_BYTES) throw new IOException("APK 超出 2 GiB 限制");
                output.write(buffer, 0, count);
            }
            if (total == 0) throw new IOException("文件为空");
            output.getFD().sync();
        }
    }

    private void showInstallResult(InstallResult result) {
        runOnUiThread(() -> {
            refresh();
            status.setText(result != null && result.success
                    ? "已安装：" + result.packageName
                    : "安装失败：" + (result == null ? "容器未返回结果" : result.msg));
        });
    }

    private void launch(String packageName) {
        try {
            ((MyboxApplication) getApplication()).requireEngine();
            if (!BlackBoxCore.get().launchApk(packageName, VIRTUAL_USER)) {
                status.setText("无法启动：" + packageName + "。请确认已安装且包含可启动界面。");
            }
        } catch (Throwable failure) { showFailure(failure); }
    }

    private void refresh() {
        try {
            ((MyboxApplication) getApplication()).requireEngine();
            List<PackageInfo> installed = BlackBoxCore.get().getInstalledPackages(0, VIRTUAL_USER);
            packages.removeAllViews();
            for (PackageInfo info : installed) {
                addButton(packages, "打开 " + info.packageName + " (" + info.versionName + ")", view -> launch(info.packageName));
                addButton(packages, "卸载 " + info.packageName, view -> new AlertDialog.Builder(this)
                        .setMessage("卸载 " + info.packageName + " 将删除它在容器中的数据和账户。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("卸载", (dialog, which) -> {
                            worker.execute(() -> {
                                try {
                                    BlackBoxCore.get().uninstallPackageAsUser(info.packageName, VIRTUAL_USER);
                                    runOnUiThread(this::refresh);
                                } catch (Throwable failure) { showFailure(failure); }
                            });
                        }).show());
            }
            status.setText("容器已初始化 · Android " + Build.VERSION.RELEASE + " · 已安装 " + installed.size() + " 个应用");
        } catch (Throwable failure) { showFailure(failure); }
    }

    private void requestHostPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(permissions.toArray(new String[0]), 2);
    }

    private void showFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        final String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        runOnUiThread(() -> status.setText("操作失败：" + message));
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
