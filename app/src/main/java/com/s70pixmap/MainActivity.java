package com.s70pixmap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单页应用：
 * 1) 授权存储卡文件夹（SAF，授权一次后永久记住）
 * 2) 用 darks 文件夹里的暗帧生成坏点映射
 * 3) 批量修复卡里的照片，输出到 S70Fixed 文件夹
 */
public class MainActivity extends Activity {

    private static final int REQ_TREE = 42;
    private static final String PREFS = "s70pixmap";
    private static final String KEY_TREE = "tree_uri";
    private static final String MAP_FILE = "pixelmap.json";
    private static final String OUT_DIR = "S70Fixed";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView logView;
    private Button pickBtn;
    private Button buildBtn;
    private Button repairBtn;
    private ProgressBar progress;

    private Uri treeUri;
    private int[] mapPoints;
    private int mapW;
    private int mapH;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_TREE, null);
        if (saved != null) {
            treeUri = Uri.parse(saved);
        }
        loadMap();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (treeUri == null) {
            promptForCard();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("S70 坏点修复");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("1. 授权存储卡  2. 用暗帧生成映射  3. 批量修复照片");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setPadding(0, 0, 0, dp(10));
        root.addView(statusView);

        pickBtn = makeButton(root, "① 选择存储卡文件夹", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFolder();
            }
        });
        buildBtn = makeButton(root, "② 用暗帧生成映射", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                buildMap();
            }
        });
        repairBtn = makeButton(root, "③ 修复卡里所有照片", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                repairAll();
            }
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logView.setPadding(0, dp(12), 0, 0);
        logView.setText("");
        root.addView(logView);

        return scroll;
    }

    private Button makeButton(LinearLayout parent, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(b, lp);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------- 存储卡识别/授权

    /** 找一张已挂载的可移动存储卷，返回描述，找不到返回 null。 */
    private String detectRemovableVolume() {
        try {
            StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
            if (sm == null) return null;
            List<StorageVolume> volumes = sm.getStorageVolumes();
            for (StorageVolume v : volumes) {
                if (!v.isRemovable()) continue;
                if (!Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                String desc = v.getDescription(this);
                return desc == null ? "可移动存储" : desc;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void promptForCard() {
        final String volume = detectRemovableVolume();
        if (volume == null) {
            log("未检测到可移动存储卡。插上读卡器后回到本页会自动提示。");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("检测到存储卡")
                .setMessage("发现：" + volume + "\n\n是否现在授权访问卡里的照片？\n（只需授权一次，之后自动记住）")
                .setPositiveButton("授权", (dialog, which) -> pickFolder())
                .setNegativeButton("稍后", null)
                .show();
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_TREE);
        } catch (Exception e) {
            toast("没有可用的文件选择器: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        treeUri = uri;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_TREE, uri.toString()).apply();
        log("已授权：" + uri);
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        if (treeUri == null) {
            sb.append("存储卡：未授权");
        } else {
            sb.append("存储卡：已授权");
        }
        sb.append("\n映射：");
        if (mapPoints == null) {
            sb.append("无（请先用暗帧生成）");
        } else {
            sb.append(mapW).append("×").append(mapH)
                    .append("，").append(mapPoints.length / 2).append(" 个点");
        }
        statusView.setText(sb.toString());
        buildBtn.setEnabled(!busy);
        repairBtn.setEnabled(!busy && mapPoints != null);
        pickBtn.setEnabled(!busy);
    }

    // ------------------------------------------------------------- 生成映射

    private void buildMap() {
        if (treeUri == null) {
            toast("请先授权存储卡文件夹");
            return;
        }
        runTask(() -> {
            List<CardScanner.Doc> all = CardScanner.scan(this, treeUri);
            List<CardScanner.Doc> darks = new ArrayList<>();
            for (CardScanner.Doc d : all) {
                if (CardScanner.isDarkFrame(d)) darks.add(d);
            }
            log("扫描到 " + all.size() + " 个文件，其中暗帧 " + darks.size() + " 张");
            if (darks.isEmpty()) {
                log("没找到暗帧。请把暗帧放进 darks 文件夹，或命名为 dark_xxx.jpg");
                return;
            }

            short[] counts = null;
            int w = 0;
            int h = 0;
            int used = 0;
            int[] pixels = null;

            for (int i = 0; i < darks.size(); i++) {
                CardScanner.Doc d = darks.get(i);
                setProgress(i + 1, darks.size(), "读取暗帧 " + d.name);
                Bitmap bm = CardScanner.decode(this, d.uri);
                if (bm == null) {
                    log("跳过无法解码的文件: " + d.name);
                    continue;
                }
                int bw = bm.getWidth();
                int bh = bm.getHeight();
                if (counts == null) {
                    w = bw;
                    h = bh;
                    counts = PixelMapEngine.newCounts(w, h);
                    pixels = new int[w * h];
                } else if (bw != w || bh != h) {
                    log("尺寸不一致，跳过: " + d.name);
                    bm.recycle();
                    continue;
                }
                bm.getPixels(pixels, 0, w, 0, 0, w, h);
                bm.recycle();

                PixelMapEngine.accumulate(pixels, w, h, PixelMapEngine.DEFAULT_THRESHOLD, counts);
                used++;
            }

            if (counts == null || used == 0) {
                log("没有可用的暗帧");
                return;
            }

            int minHits = Math.max(1, (int) Math.ceil(PixelMapEngine.DEFAULT_COVERAGE * used));
            int[] points = PixelMapEngine.pointsFromCounts(counts, w, h, minHits, 1);

            mapW = w;
            mapH = h;
            mapPoints = points;
            saveMap();
            runOnUiThread(this::updateStatus);
            log("映射完成：" + w + "×" + h + "，坏点 " + (points.length / 2)
                    + " 个（已含 1 像素外扩）");
        });
    }

    // ------------------------------------------------------------- 批量修复

    private void repairAll() {
        if (treeUri == null) {
            toast("请先授权存储卡文件夹");
            return;
        }
        if (mapPoints == null) {
            toast("请先生成映射");
            return;
        }
        runTask(() -> {
            try {
                repairPhotosOnCard();
            } catch (Exception e) {
                log("修复中断: " + e);
            }
        });
    }

    /** 真正干活的方法；允许抛受检异常，由上面的 lambda 统一兜住。 */
    private void repairPhotosOnCard() throws Exception {
            List<CardScanner.Doc> all = CardScanner.scan(this, treeUri);
            List<CardScanner.Doc> photos = new ArrayList<>();
            for (CardScanner.Doc d : all) {
                if (!CardScanner.isJpeg(d)) continue;
                if (CardScanner.isDarkFrame(d)) continue;
                if (OUT_DIR.equalsIgnoreCase(d.folder)) continue;
                photos.add(d);
            }
            log("找到 " + photos.size() + " 张待修复照片");
            if (photos.isEmpty()) {
                log("没有需要修复的照片");
                return;
            }

            Uri outDir = CardScanner.ensureDir(this, treeUri, OUT_DIR);
            boolean[] mask = null;
            int done = 0;
            int skipped = 0;
            int fixedPixels = 0;

            for (int i = 0; i < photos.size(); i++) {
                CardScanner.Doc d = photos.get(i);
                setProgress(i + 1, photos.size(), "修复 " + d.name);
                Bitmap bm = CardScanner.decode(this, d.uri);
                if (bm == null) {
                    log("跳过无法解码的文件: " + d.name);
                    skipped++;
                    continue;
                }
                int w = bm.getWidth();
                int h = bm.getHeight();
                if (w != mapW || h != mapH) {
                    log("尺寸与映射不符，跳过: " + d.name);
                    bm.recycle();
                    skipped++;
                    continue;
                }
                int[] pixels = new int[w * h];
                bm.getPixels(pixels, 0, w, 0, 0, w, h);
                bm.recycle();

                if (mask == null) {
                    mask = PixelMapEngine.maskFromPoints(mapPoints, w, h, 0);
                }
                fixedPixels += PixelMapEngine.repair(pixels, w, h, mask);

                Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                out.setPixels(pixels, 0, w, 0, 0, w, h);
                Uri dst = CardScanner.createFile(this, treeUri, outDir, "image/jpeg", d.name);
                OutputStream os = getContentResolver().openOutputStream(dst);
                if (os == null) {
                    log("无法写入: " + d.name);
                    out.recycle();
                    skipped++;
                    continue;
                }
                try {
                    out.compress(Bitmap.CompressFormat.JPEG, 95, os);
                } finally {
                    os.close();
                    out.recycle();
                }
                done++;
            }

            log("完成：" + done + " 张，替换 " + fixedPixels + " 个像素，跳过 " + skipped
                    + " 张。输出在存储卡的 " + OUT_DIR + " 文件夹。");
    }

    // ------------------------------------------------------------ 映射存取

    private void saveMap() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("w", mapW);
            obj.put("h", mapH);
            JSONArray arr = new JSONArray();
            for (int i = 0; i + 1 < mapPoints.length; i += 2) {
                JSONArray p = new JSONArray();
                p.put(mapPoints[i]);
                p.put(mapPoints[i + 1]);
                arr.put(p);
            }
            obj.put("points", arr);

            File f = new File(getFilesDir(), MAP_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(obj.toString().getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (Exception e) {
            log("保存映射失败: " + e);
        }
    }

    private void loadMap() {
        File f = new File(getFilesDir(), MAP_FILE);
        if (!f.exists()) return;
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            int read = 0;
            try {
                while (read < buf.length) {
                    int n = fis.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            } finally {
                fis.close();
            }
            JSONObject obj = new JSONObject(new String(buf, 0, read, "UTF-8"));
            mapW = obj.getInt("w");
            mapH = obj.getInt("h");
            JSONArray arr = obj.getJSONArray("points");
            int[] points = new int[arr.length() * 2];
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.getJSONArray(i);
                points[i * 2] = p.getInt(0);
                points[i * 2 + 1] = p.getInt(1);
            }
            mapPoints = points;
            log("已载入映射：" + mapW + "×" + mapH + "，" + arr.length() + " 个点");
        } catch (Exception e) {
            log("映射文件损坏，已忽略: " + e);
            mapPoints = null;
        }
    }

    // --------------------------------------------------------------- 工具

    private void runTask(Runnable task) {
        if (busy) {
            toast("正在处理，请稍候");
            return;
        }
        busy = true;
        updateStatus();
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);

        worker.execute(() -> {
            try {
                task.run();
            } catch (OutOfMemoryError e) {
                log("内存不足：照片太大，或一次处理的文件太多。");
            } catch (Throwable t) {
                log("出错: " + t.getClass().getSimpleName() + " " + t.getMessage());
            } finally {
                ui.post(() -> {
                    busy = false;
                    progress.setIndeterminate(false);
                    progress.setVisibility(View.GONE);
                    updateStatus();
                });
            }
        });
    }

    private void setProgress(int value, int total, String message) {
        ui.post(() -> {
            progress.setIndeterminate(false);
            progress.setMax(total);
            progress.setProgress(value);
            statusView.setText(message + "  (" + value + "/" + total + ")");
        });
    }

    private void log(String message) {
        ui.post(() -> logView.append(message + "\n"));
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
