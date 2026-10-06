package com.mcai.ubuntudsu.core.dna;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mcai.ubuntudsu.core.DnaTools;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v3.30.15：内置文件浏览器（root 列目录，玻璃风格）—— 替换系统 SAF 选择器。
 * 系统文件选择器层级深、找文件麻烦；本浏览器支持快捷路径跳转、目录导航、
 * 扩展名过滤、大小显示，选中直接回调真实绝对路径（root 命令可直接使用）。
 */
public class FileBrowserDialog {

    public interface OnFilePicked {
        void onPicked(String path);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-browser");
        t.setDaemon(true);
        return t;
    });

    private final Activity act;
    private final Dialog dialog;
    private final com.mcai.ubuntudsu.ui.Ui.DnaPalette pal;
    private final String[] exts;          // null = 全部文件；endsWith 匹配（带点，如 ".img"）
    private final OnFilePicked cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String curDir;
    private TextView pathView;
    private LinearLayout list;
    private TextView loadingHint;

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private String tr(String zh, String en) {
        return Locale.getDefault().getLanguage().equals("zh") ? zh : en;
    }

    private FileBrowserDialog(Activity act, String title, String[] exts,
                              String startDir, OnFilePicked cb) {
        this.act = act;
        this.pal = com.mcai.ubuntudsu.ui.Ui.INSTANCE.dnaPalette(act);
        this.exts = exts;
        this.cb = cb;
        this.curDir = startDir != null && startDir.startsWith("/") ? startDir
                : "/storage/emulated/0";
        this.dialog = new Dialog(act);
        dialog.setCancelable(true);
        buildUi(title);
    }

    /** 显示文件浏览器。exts 为 null 时显示全部文件 */
    public static void show(Activity act, String title, String[] exts,
                            String startDir, OnFilePicked cb) {
        new FileBrowserDialog(act, title, exts, startDir, cb).open();
    }

    private void open() {
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0x00000000));
        dialog.show();
        Window w2 = dialog.getWindow();
        if (w2 != null) {
            w2.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.94f),
                    dp(580));
        }
        load(curDir);
    }

    // ============ UI 构建 ============

    private void buildUi(String title) {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        panel.setBackground(bg);

        // ---- 标题行 ----
        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(act);
        titleView.setText(title);
        titleView.setTextSize(16f);
        titleView.setTypeface(null, 1);
        titleView.setTextColor(0xff17334f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        Button close = new Button(act, null, 0);
        close.setText("✕");
        close.setTextSize(14);
        close.setAllCaps(false);
        close.setMinWidth(0);
        close.setMinHeight(0);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, 0, 0, 0);
        close.setTextColor(0xff5a6b82);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(0x59FFFFFF);
        closeBg.setCornerRadius(dp(12));
        close.setBackground(closeBg);
        close.setStateListAnimator(null);
        close.setOnClickListener(v -> dialog.dismiss());
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        panel.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        // ---- 路径行：上级按钮 + 当前路径 ----
        LinearLayout pathRow = new LinearLayout(act);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        pathRow.setPadding(0, dp(10), 0, 0);
        Button up = new Button(act, null, 0);
        up.setText("↑");
        up.setTextSize(15);
        up.setAllCaps(false);
        up.setMinWidth(0);
        up.setMinHeight(0);
        up.setGravity(Gravity.CENTER);
        up.setPadding(0, 0, 0, 0);
        up.setTextColor(0xff172b4d);
        GradientDrawable upBg = new GradientDrawable();
        upBg.setColor(0x59FFFFFF);
        upBg.setCornerRadius(dp(12));
        up.setBackground(upBg);
        up.setStateListAnimator(null);
        up.setOnClickListener(v -> {
            Haptics.perform(v);
            File parent = new File(curDir).getParentFile();
            if (parent != null && parent.getPath().startsWith("/"))
                load(parent.getPath());
        });
        pathRow.addView(up, new LinearLayout.LayoutParams(dp(34), dp(30)));
        pathView = new TextView(act);
        pathView.setTextSize(11.5f);
        pathView.setTextColor(0xff35A8C4);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(0, -2, 1f);
        pvLp.leftMargin = dp(8);
        pathRow.addView(pathView, pvLp);
        panel.addView(pathRow, new LinearLayout.LayoutParams(-1, -2));

        // ---- 快捷路径 chips ----
        HorizontalScrollView quickScroll = new HorizontalScrollView(act);
        quickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout quickRow = new LinearLayout(act);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickScroll.addView(quickRow, new HorizontalScrollView.LayoutParams(-2, -2));
        addQuick(quickRow, "💾 " + tr("内部存储", "Storage"), "/storage/emulated/0");
        addQuick(quickRow, "🧬 PDNA", "/storage/emulated/0/PDNA");
        addQuick(quickRow, "📥 Download", "/storage/emulated/0/Download");
        addQuick(quickRow, "📷 DCIM", "/storage/emulated/0/DCIM");
        addQuick(quickRow, "🧪 tmp", "/data/local/tmp");
        LinearLayout.LayoutParams qsLp = new LinearLayout.LayoutParams(-1, -2);
        qsLp.topMargin = dp(8);
        panel.addView(quickScroll, qsLp);

        // ---- 文件列表 ----
        ScrollView scroll = new ScrollView(act);
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lsLp.topMargin = dp(10);
        panel.addView(scroll, lsLp);

        loadingHint = new TextView(act);
        loadingHint.setText(tr("加载中 ...", "Loading..."));
        loadingHint.setTextSize(12.5f);
        loadingHint.setTextColor(0xff5a6b82);
        loadingHint.setPadding(dp(4), dp(10), 0, 0);

        // ---- 底部取消 ----
        Button cancel = new Button(act, null, 0);
        cancel.setText(tr("取消", "Cancel"));
        cancel.setAllCaps(false);
        cancel.setTextColor(0xff172b4d);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, 0, 0, 0);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(0x59FFFFFF);
        cancelBg.setCornerRadius(dp(16));
        cancelBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        cancel.setBackground(cancelBg);
        cancel.setStateListAnimator(null);
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, dp(44));
        cLp.topMargin = dp(10);
        panel.addView(cancel, cLp);

        dialog.setContentView(panel, new ViewGroup.LayoutParams(-1, dp(580)));
    }

    private void addQuick(LinearLayout host, String label, String dir) {
        Button chip = new Button(act, null, 0);
        chip.setText(label);
        chip.setTextSize(11.5f);
        chip.setAllCaps(false);
        chip.setMinWidth(0);
        chip.setMinHeight(0);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setTextColor(0xff17334f);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0x33FFFFFF);
        cb.setCornerRadius(dp(14));
        cb.setStroke(Math.max(1, dp(1)), 0x4DFFFFFF);
        chip.setBackground(cb);
        chip.setStateListAnimator(null);
        chip.setOnClickListener(v -> {
            Haptics.perform(v);
            load(dir);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30));
        lp.rightMargin = dp(6);
        host.addView(chip, lp);
    }

    // ============ 目录加载与渲染 ============

    private void load(final String dir) {
        curDir = dir;
        pathView.setText(dir);
        list.removeAllViews();
        list.addView(loadingHint, new LinearLayout.LayoutParams(-1, -2));
        IO.execute(() -> {
            final List<DnaTools.BrowseEntry> entries = DnaTools.browseDir(dir);
            main.post(() -> render(entries));
        });
    }

    private void render(List<DnaTools.BrowseEntry> entries) {
        if (act.isFinishing() || !dialog.isShowing()) return;
        list.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText(tr("空目录或无法读取", "Empty or unreadable"));
            empty.setTextSize(12.5f);
            empty.setTextColor(0xff5a6b82);
            empty.setPadding(dp(4), dp(10), 0, 0);
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        int fileCount = 0;
        for (DnaTools.BrowseEntry e : entries) {
            if (e.isDir()) {
                list.addView(dirRow(e.getName()), rowLp());
            } else if (matchExt(e.getName())) {
                fileCount++;
                list.addView(fileRow(e.getName(), e.getSize()), rowLp());
            }
        }
        if (fileCount == 0 && exts != null) {
            TextView none = new TextView(act);
            none.setText(tr("此目录没有匹配的文件（" + joinExts() + "）", "No matching files here (" + joinExts() + ")"));
            none.setTextSize(12.5f);
            none.setTextColor(0xff5a6b82);
            none.setPadding(dp(4), dp(10), 0, 0);
            list.addView(none, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(5);
        return lp;
    }

    private boolean matchExt(String name) {
        if (exts == null || exts.length == 0) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : exts) if (lower.endsWith(ext.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private String joinExts() {
        StringBuilder sb = new StringBuilder();
        for (String ext : exts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("*").append(ext);
        }
        return sb.toString();
    }

    private LinearLayout dirRow(final String name) {
        LinearLayout row = baseRow();
        row.setOnClickListener(v -> {
            Haptics.perform(v);
            load(join(name));
        });
        addIcon(row, "📁");
        addName(row, name, null);
        addTail(row, "", pal.subtitle);
        return row;
    }

    /** v3.30.16 修复：条目名若为绝对路径则直接使用，避免与 curDir 拼出 /a//a/b 双重路径 */
    private String join(String name) {
        if (name.startsWith("/")) return name;
        return curDir.endsWith("/") ? curDir + name : curDir + "/" + name;
    }

    private LinearLayout fileRow(final String name, long size) {
        final LinearLayout row = baseRow();
        row.setOnClickListener(v -> {
            Haptics.perform(v);
            dialog.dismiss();
            cb.onPicked(join(name));
        });
        addIcon(row, fileIcon(name));
        addName(row, name, 0xff17334f);
        addTail(row, size > 0 ? formatSize(size) : "", 0xff5a6b82);
        return row;
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(11), dp(11), dp(11), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x22FFFFFF);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        row.setBackground(bg);
        return row;
    }

    private void addIcon(LinearLayout row, String icon) {
        TextView tv = new TextView(act);
        tv.setText(icon);
        tv.setTextSize(15);
        row.addView(tv, new LinearLayout.LayoutParams(-2, -2));
    }

    private void addName(LinearLayout row, String name, Integer color) {
        TextView tv = new TextView(act);
        tv.setText(name);
        tv.setTextSize(13.5f);
        tv.setTextColor(color != null ? color : 0xff17334f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(9);
        row.addView(tv, lp);
    }

    private void addTail(LinearLayout row, String text, int color) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTextColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        row.addView(tv, lp);
    }

    private String fileIcon(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".img") || n.endsWith(".raw")) return "🧱";
        if (n.endsWith(".zip") || n.endsWith(".zip2")) return "📦";
        if (n.endsWith("payload.bin")) return "🧬";
        if (n.endsWith(".zst") || n.endsWith(".zstd") || n.endsWith(".gz")
                || n.endsWith(".xz") || n.endsWith(".tgz") || n.endsWith(".tar") || n.endsWith(".br")) return "🗜";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".bmp")) return "🖼";
        if (n.endsWith(".bin")) return "⚙";
        return "📄";
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.2f GB", bytes / 1073741824d);
        if (bytes >= 1024L * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / 1048576d);
        if (bytes >= 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        return bytes + " B";
    }
}
