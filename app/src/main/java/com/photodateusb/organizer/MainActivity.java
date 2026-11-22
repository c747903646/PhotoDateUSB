package com.photodateusb.organizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-screen, dependency-free Chinese UI.
 * Purposefully uses Android's system document picker instead of broad storage
 * permissions. No network permission; no photo contents leave the phone/USB.
 */
public final class MainActivity extends Activity {
    private static final int PICK_SOURCE = 101;
    private static final int PICK_TARGET = 102;
    private static final int INK = Color.rgb(25, 57, 77);
    private static final int TEAL = Color.rgb(23, 144, 138);
    private static final int BODY = Color.rgb(63, 87, 104);
    private static final int MUTED = Color.rgb(103, 125, 137);
    private static final int BG = Color.rgb(247, 249, 248);
    private static final int PALE = Color.rgb(228, 244, 239);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private TransferEngine engine;
    private TransferEngine.Scan currentScan;
    private Uri sourceUri, targetUri;
    private boolean running;

    private TextView sourceLabel, targetLabel, summaryLabel, progressLabel, previewLabel;
    private Button sourceButton, targetButton, scanButton, moveButton, stopButton;
    private ProgressBar progressBar;
    private RadioGroup grouping;
    private RadioButton byYear, byMonth, byDay;
    private CheckBox recursive, filenameFallback, modifiedFallback, shaCheck;
    private SharedPreferences saved;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        engine = new TransferEngine(this);
        saved = getSharedPreferences("folders", MODE_PRIVATE);
        String source = saved.getString("source", "");
        String target = saved.getString("target", "");
        if (!source.isEmpty()) sourceUri = Uri.parse(source);
        if (!target.isEmpty()) targetUri = Uri.parse(target);
        buildUi();
        refreshFolderLabels();
        refreshButtons();
    }

    private int dp(float n) {
        return (int) (getResources().getDisplayMetrics().density * n + 0.5f);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setLineSpacing(dp(3), 1f);
        return v;
    }

    private void gap(LinearLayout container, int heightDp) {
        View v = new View(this);
        container.addView(v, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = vertical();
        box.setBackground(rounded(Color.WHITE, 20));
        box.setPadding(dp(18), dp(16), dp(18), dp(18));
        box.setElevation(dp(2));
        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2);
        p.bottomMargin = dp(14);
        parent.addView(box, p);
        return box;
    }

    private Button button(String title, boolean primary) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : INK);
        b.setBackground(rounded(primary ? TEAL : PALE, 13));
        b.setMinHeight(dp(50));
        return b;
    }

    private void addFullButton(LinearLayout parent, Button b) {
        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        parent.addView(b, p);
    }

    private CheckBox check(String title, boolean enabled) {
        CheckBox v = new CheckBox(this);
        v.setText(title);
        v.setTextSize(13);
        v.setTextColor(BODY);
        v.setChecked(enabled);
        v.setPadding(0, dp(4), 0, dp(4));
        v.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
        v.setOnCheckedChangeListener((button, checked) -> invalidateScan());
        return v;
    }

    private RadioButton radio(String title) {
        RadioButton v = new RadioButton(this);
        v.setId(View.generateViewId());
        v.setText(title);
        v.setTextSize(14);
        v.setTextColor(BODY);
        v.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
        return v;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = vertical();
        page.setPadding(dp(20), dp(23), dp(20), dp(28));
        scroll.addView(page);

        TextView brand = text("LIGHTBOX   /   PHONE → USB", 10, TEAL, true);
        brand.setLetterSpacing(0.13f);
        page.addView(brand);
        gap(page, 7);
        page.addView(text("照片按日期归档", 27, INK, true));
        gap(page, 5);
        page.addView(text("直接从安卓手机移动到 Type-C U 盘\n原片不压缩 · 按拍摄年月日分类", 13, MUTED, false));
        gap(page, 20);

        LinearLayout source = card(page);
        source.addView(text("01   手机照片来源", 17, INK, true));
        gap(source, 6);
        source.addView(text("建议选择：内部存储 / DCIM / Camera", 12, MUTED, false));
        gap(source, 12);
        sourceButton = button("选择手机照片文件夹", false);
        addFullButton(source, sourceButton);
        sourceButton.setOnClickListener(v -> pickFolder(PICK_SOURCE));
        gap(source, 9);
        sourceLabel = text("尚未选择", 12, BODY, false);
        sourceLabel.setTextIsSelectable(true);
        source.addView(sourceLabel);

        LinearLayout target = card(page);
        target.addView(text("02   Type-C U 盘目标", 17, INK, true));
        gap(target, 6);
        target.addView(text("请先在 U 盘内创建一个“照片归档”文件夹，再选择它", 12, MUTED, false));
        gap(target, 12);
        targetButton = button("选择 U 盘目标文件夹", false);
        addFullButton(target, targetButton);
        targetButton.setOnClickListener(v -> pickFolder(PICK_TARGET));
        gap(target, 9);
        targetLabel = text("尚未选择", 12, BODY, false);
        targetLabel.setTextIsSelectable(true);
        target.addView(targetLabel);

        LinearLayout classify = card(page);
        classify.addView(text("03   按拍摄日期分类", 17, INK, true));
        gap(classify, 12);
        grouping = new RadioGroup(this);
        grouping.setOrientation(LinearLayout.VERTICAL);
        byYear = radio("按年          2024/");
        byMonth = radio("按年月      2024/05/");
        byDay = radio("按年月日  2024/05/18/");
        grouping.addView(byYear);
        grouping.addView(byMonth);
        grouping.addView(byDay);
        byMonth.setChecked(true);
        grouping.setOnCheckedChangeListener((group, id) -> invalidateScan());
        classify.addView(grouping);
        gap(classify, 9);
        recursive = check("同时扫描来源文件夹中的子文件夹", true);
        filenameFallback = check("EXIF 缺失时，从文件名推测日期", true);
        modifiedFallback = check("仍无法判断时，使用文件修改日期（不推荐）", false);
        shaCheck = check("移动前校验 SHA-256，避免丢失原件", true);
        classify.addView(recursive);
        classify.addView(filenameFallback);
        classify.addView(modifiedFallback);
        classify.addView(shaCheck);
        gap(classify, 4);
        classify.addView(text(
            "日期优先级：原始 EXIF 拍摄时间 → EXIF 数字化时间 → 文件名 → 可选修改时间。无日期的照片放到“未识别日期”。",
            12, MUTED, false));

        LinearLayout actions = card(page);
        actions.addView(text("04   扫描预览，再确认移动", 17, INK, true));
        gap(actions, 8);
        scanButton = button("① 扫描并预览", false);
        addFullButton(actions, scanButton);
        scanButton.setOnClickListener(v -> beginScan());
        gap(actions, 10);
        moveButton = button("② 确认安全移动", true);
        addFullButton(actions, moveButton);
        moveButton.setOnClickListener(v -> confirmMove());
        gap(actions, 10);
        stopButton = button("停止当前任务", false);
        addFullButton(actions, stopButton);
        stopButton.setOnClickListener(v -> {
            cancelled.set(true);
            progressLabel.setText("正在停止：当前照片会清理临时文件后安全结束…");
        });
        gap(actions, 13);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));
        actions.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(8)));
        gap(actions, 9);
        progressLabel = text("请选择两个文件夹，再点击扫描。", 13, BODY, false);
        actions.addView(progressLabel);

        LinearLayout preview = card(page);
        preview.addView(text("照片整理预览", 17, INK, true));
        gap(preview, 9);
        summaryLabel = text("还没有扫描照片。", 13, TEAL, true);
        preview.addView(summaryLabel);
        gap(preview, 9);
        previewLabel = text("将在这里显示照片日期、依据和归档路径。\n每次最多预览前 40 张，实际处理所有照片。", 12, BODY, false);
        previewLabel.setTextIsSelectable(true);
        previewLabel.setMovementMethod(new ScrollingMovementMethod());
        preview.addView(previewLabel);

        gap(page, 6);
        TextView footer = text(
            "安全提示：请保持手机解锁、屏幕亮着，U 盘不要拔出。\n移动操作会删除已成功校验的手机原件；重要照片请先另存一份备份。",
            12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        page.addView(footer);
        setContentView(scroll);
    }

    private void pickFolder(int which) {
        if (running) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, which);
        } catch (Exception e) {
            alert("无法打开文件选择器", "请检查系统文件管理服务是否正常：" + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri chosen = data.getData();
        try {
            if (request == PICK_TARGET &&
                "com.android.externalstorage.documents".equals(chosen.getAuthority()) &&
                DocumentsContract.getTreeDocumentId(chosen).startsWith("primary:")) {
                alert("请选择真正的 Type-C U 盘",
                    "当前选择的是手机内部存储，不是 U 盘。\\n\\n" +
                    "请在系统文件选择器侧边栏选择 USB 驱动器，" +
                    "再进入 U 盘内的“照片归档”文件夹。");
                return;
            }
            int grantFlags = data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 ||
                (grantFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                throw new SecurityException("没有获得完整读写授权。移动照片需要能够删除手机原件、写入 U 盘。");
            }
            getContentResolver().takePersistableUriPermission(chosen, grantFlags);
            if (request == PICK_SOURCE) {
                sourceUri = chosen;
                saved.edit().putString("source", chosen.toString()).apply();
            } else if (request == PICK_TARGET) {
                targetUri = chosen;
                saved.edit().putString("target", chosen.toString()).apply();
            }
            invalidateScan();
            refreshFolderLabels();
            refreshButtons();
        } catch (Exception e) {
            alert("文件夹授权失败", e.getMessage() +
                "\n\n请选择系统文件管理器中的普通文件夹，避免 Android/data 或存储根目录。");
        }
    }

    private String locationLabel(Uri uri) {
        if (uri == null) return "尚未选择";
        try {
            return uri.getAuthority() + "\n" + DocumentsContract.getTreeDocumentId(uri);
        } catch (Exception ignored) {
            return uri.toString();
        }
    }

    private void refreshFolderLabels() {
        sourceLabel.setText(locationLabel(sourceUri));
        targetLabel.setText(locationLabel(targetUri));
    }

    private void invalidateScan() {
        if (running) return;
        currentScan = null;
        if (summaryLabel != null) summaryLabel.setText("设置已改变，请重新扫描。");
        if (previewLabel != null) previewLabel.setText("");
        refreshButtons();
    }

    private void refreshButtons() {
        if (scanButton == null) return;
        sourceButton.setEnabled(!running);
        targetButton.setEnabled(!running);
        scanButton.setEnabled(!running && sourceUri != null && targetUri != null);
        moveButton.setEnabled(!running && currentScan != null && !currentScan.photos.isEmpty());
        stopButton.setEnabled(running);
        grouping.setEnabled(!running);
        for (int i = 0; i < grouping.getChildCount(); i++) {
            grouping.getChildAt(i).setEnabled(!running);
        }
        recursive.setEnabled(!running);
        filenameFallback.setEnabled(!running);
        modifiedFallback.setEnabled(!running);
        shaCheck.setEnabled(!running);
    }

    private TransferEngine.Options makeOptions() {
        TransferEngine.Options o = new TransferEngine.Options();
        o.sourceTree = sourceUri;
        o.targetTree = targetUri;
        o.group = byYear.isChecked() ? DateRules.Group.YEAR :
            byDay.isChecked() ? DateRules.Group.DAY : DateRules.Group.MONTH;
        o.recursive = recursive.isChecked();
        o.filenameDate = filenameFallback.isChecked();
        o.modifiedDate = modifiedFallback.isChecked();
        o.verifyHash = shaCheck.isChecked();
        return o;
    }

    private void setWorking(boolean busy, boolean indeterminate, int total) {
        running = busy;
        if (busy) {
            cancelled.set(false);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) {
            progressBar.setMax(Math.max(total, 1));
            progressBar.setProgress(0);
        }
        refreshButtons();
    }

    private void beginScan() {
        if (running) return;
        final TransferEngine.Options snapshot = makeOptions();
        try {
            TransferEngine.assertSafeTrees(snapshot.sourceTree, snapshot.targetTree);
        } catch (Exception e) {
            alert("文件夹选择有误", e.getMessage());
            return;
        }
        currentScan = null;
        summaryLabel.setText("正在读取照片 EXIF 信息…");
        previewLabel.setText("");
        progressLabel.setText("正在扫描，请保持应用在前台。");
        setWorking(true, true, 0);
        executor.execute(() -> {
            try {
                TransferEngine.Scan scan = engine.scan(snapshot, cancelled, this::progressFromWorker);
                ui.post(() -> {
                    currentScan = scan;
                    setWorking(false, false, scan.photos.size());
                    displayScan(scan);
                });
            } catch (CancellationException e) {
                ui.post(() -> {
                    setWorking(false, false, 0);
                    progressLabel.setText("扫描已停止，没有移动照片。");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setWorking(false, false, 0);
                    alert("扫描失败", e.getMessage());
                    progressLabel.setText("扫描失败，请检查存储权限或 U 盘连接。");
                });
            }
        });
    }

    private void progressFromWorker(int done, int total, String description) {
        ui.post(() -> {
            progressLabel.setText(description);
            if (!progressBar.isIndeterminate() && total > 0) {
                progressBar.setMax(total);
                progressBar.setProgress(done);
            }
        });
    }

    private void displayScan(TransferEngine.Scan scan) {
        summaryLabel.setText(scan.summary());
        if (scan.photos.isEmpty()) {
            previewLabel.setText("所选来源目录没有找到支持的图片文件。");
            progressLabel.setText("扫描完成：0 张照片。");
            return;
        }
        StringBuilder buf = new StringBuilder();
        int limit = Math.min(scan.photos.size(), 40);
        for (int i = 0; i < limit; i++) {
            TransferEngine.Photo p = scan.photos.get(i);
            buf.append(i + 1).append(".  ")
                .append(p.sourceRelative).append("\n")
                .append("    ")
                .append(DateRules.show(p.captureDate)).append("  [")
                .append(p.dateSource).append("]\n")
                .append("    → ")
                .append(p.destinationPreview()).append("\n\n");
        }
        if (scan.photos.size() > limit) {
            buf.append("……其余 ").append(scan.photos.size() - limit)
                .append(" 张未显示，但会一起移动。");
        }
        previewLabel.setText(buf.toString());
        progressLabel.setText("预览就绪。请确认日期和来源文件夹，再执行移动。");
    }

    private void confirmMove() {
        if (running || currentScan == null) return;
        final TransferEngine.Scan scan = currentScan;
        final TransferEngine.Options snapshot = makeOptions();
        new AlertDialog.Builder(this)
            .setTitle("确认将手机照片移动到 U 盘？")
            .setMessage("待处理 " + scan.photos.size() + " 张。\n\n" +
                "分类方式：" + (snapshot.group == DateRules.Group.YEAR ? "年" :
                snapshot.group == DateRules.Group.MONTH ? "年月" : "年月日") + "\n" +
                "未识别日期：" + scan.unknownCount + " 张，单独归档。\n\n" +
                "每张照片在 U 盘复制、校验成功后，才会删除手机原件。\n" +
                "建议先备份，并用 10 张照片试运行。\n\n" +
                "请保持屏幕亮着，不要拔出 U 盘。")
            .setNegativeButton("暂不移动", null)
            .setPositiveButton("开始移动", (dialog, which) -> beginMove(snapshot, scan))
            .show();
    }

    private void beginMove(TransferEngine.Options options, TransferEngine.Scan scan) {
        setWorking(true, false, scan.photos.size());
        progressLabel.setText("正在准备安全移动…");
        executor.execute(() -> {
            try {
                TransferEngine.Outcome out = engine.move(options, scan, cancelled, this::progressFromWorker);
                ui.post(() -> {
                    currentScan = null;
                    setWorking(false, false, scan.photos.size());
                    progressBar.setProgress(out.processed);
                    summaryLabel.setText(out.summary());
                    previewLabel.setText(
                        "本次移动结束，请检查 U 盘文件夹。\n" +
                        (out.usbLog != null ? "U 盘日志：" + out.usbLog + "\n" :
                         "日志未成功导出到 U 盘，请不要盲目删除任何文件。\n") +
                        (out.lastError != null ? "\n最近一次错误：" + out.lastError + "\n" : "") +
                        (out.logError != null ? "\n日志导出错误：" + out.logError : "")
                    );
                    progressLabel.setText(out.summary());
                    alert("操作结束", out.summary() +
                        "\n\n成功复制后才尝试删除手机原件。\n" +
                        "请核对 U 盘文件和 CSV 日志，完成后重新扫描剩余照片。");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    currentScan = null;
                    setWorking(false, false, 0);
                    progressLabel.setText("运行中发生错误，手机照片不应被整体删除。");
                    alert("移动中断", "请检查手机与 U 盘上的文件以及操作日志。\n" + e.getMessage());
                });
            }
        });
    }

    private void alert(String title, String message) {
        new AlertDialog.Builder(this)
            .setTitle(title).setMessage(message)
            .setPositiveButton("知道了", null).show();
    }

    @Override public void onBackPressed() {
        if (running) {
            new AlertDialog.Builder(this)
                .setTitle("仍在整理照片")
                .setMessage("返回会请求停止任务。当前照片的临时文件会安全清理。")
                .setNegativeButton("继续整理", null)
                .setPositiveButton("停止任务", (d, w) -> {
                    cancelled.set(true);
                    progressLabel.setText("正在安全停止当前任务…");
                })
                .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        // Worker is not tied to Activity destruction; best effort stop.
        cancelled.set(true);
        executor.shutdown();
        super.onDestroy();
    }
}
