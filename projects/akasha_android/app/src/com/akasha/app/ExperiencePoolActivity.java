package com.akasha.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Experience pool viewer (单一 Activity 复用，传 pool_id 显示不同池；无传参默认 GLOBAL)。
 * 方案 §11: 不要复制页面；每个发现页项跳入同一个 Activity 并传不同 pool_id。
 *  - User delete via remove() → Experience 本体删，experience_pool 自动级联
 *  - Publish (用户发布)：新写的经验进入当前池（同时默认仍加入 GLOBAL 池）
 */
public class ExperiencePoolActivity extends Activity {

    public static final String EXTRA_POOL_ID = "pool_id";
    public static final String EXTRA_POOL_NAME = "pool_name";
    private static final int REQ_PUBLISH = 1;

    private ExpStore store;
    private ListView lv;
    private TextView tvEmpty, tvTitle;
    private EditText etSearch;
    private List<Experience> items = new ArrayList<>();
    private PoolAdapter adapter;
    private String poolId = PoolInfo.GLOBAL_ID;
    private String poolName = "全局经验池";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exp_pool);
        store = new ExpStore(this);

        Intent i = getIntent();
        if (i != null) {
            String s = i.getStringExtra(EXTRA_POOL_ID);
            if (s != null && !s.isEmpty()) poolId = s;
            String n = i.getStringExtra(EXTRA_POOL_NAME);
            if (n != null && !n.isEmpty()) poolName = n;
        }

        lv = (ListView) findViewById(R.id.lvPool);
        tvEmpty = (TextView) findViewById(R.id.tvPoolEmpty);
        etSearch = (EditText) findViewById(R.id.etPoolSearch);
        tvTitle = (TextView) findViewById(R.id.tvPoolTitle);
        adapter = new PoolAdapter();
        lv.setAdapter(adapter);

        if (tvTitle != null) tvTitle.setText(poolName);
        // 如果池被禁用了，在标题上提示一下
        PoolInfo me = store.getPool(poolId);
        if (me != null && !me.enabled) {
            CharSequence cur = tvTitle == null ? "" : tvTitle.getText();
            if (tvTitle != null) tvTitle.setText("❌ " + cur + " （已禁用，Agent 暂不可读写）");
        }

        findViewById(R.id.btnPoolBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        findViewById(R.id.btnPoolPublish).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent pi = new Intent(ExperiencePoolActivity.this,
                        PublishExperienceActivity.class);
                pi.putExtra(PublishExperienceActivity.EXTRA_POOL_ID, poolId);
                startActivityForResult(pi, REQ_PUBLISH);
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PUBLISH && resultCode == RESULT_OK) refresh();
        if (requestCode == 100) refresh();
    }

    private void refresh() {
        String q = etSearch.getText().toString().trim();
        if (q.isEmpty()) {
            items = store.listForPool(poolId);
        } else {
            // 指定池内局部搜索（兼容旧行为）
            // 这里直接复用 ExpStore.searchInPool 替代：为避免把 private 打开，
            // 用 searchForAgent(user 视角) → 退而求其次: 简单 listForPool 后过滤
            List<Experience> full = store.listForPool(poolId);
            String lq = q.toLowerCase(java.util.Locale.ROOT);
            items = new ArrayList<>();
            for (Experience e : full) {
                if (contains(e.title, lq) || contains(e.content, lq)
                        || contains(e.agentName, lq)) items.add(e);
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static boolean contains(String s, String lowerQ) {
        return s != null && s.toLowerCase(java.util.Locale.ROOT).contains(lowerQ);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Sample-decode a JPEG bitmap into target px to avoid OOM (the root cause of
     *  "tap image → jump home" bug on user-published high-res photos). */
    static Bitmap decodeSampled(String path, int targetPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            return decodeSampledInternal(o.outWidth, o.outHeight, targetPx,
                    new java.util.concurrent.Callable<Bitmap>() {
                        @Override
                        public Bitmap call() throws Exception {
                            return BitmapFactory.decodeFile(path, (BitmapFactory.Options) null);
                        }
                    });
        } catch (Throwable t) { // OutOfMemoryError too
            return null;
        }
    }

    /** @param is a fresh InputStream (not yet read); caller closes it is optional here. */
    static Bitmap decodeSampled(InputStream is, int targetPx) {
        if (is == null) return null;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] all = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(all, 0, all.length, o);
            return decodeSampledInternal(o.outWidth, o.outHeight, targetPx,
                    new java.util.concurrent.Callable<Bitmap>() {
                        @Override
                        public Bitmap call() throws Exception {
                            return BitmapFactory.decodeByteArray(all, 0, all.length,
                                    (BitmapFactory.Options) null);
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decodeSampledInternal(int w, int h, int targetPx,
            java.util.concurrent.Callable<Bitmap> decodeFull) throws Exception {
        int scale = 1;
        int W = w > 0 ? w : targetPx;
        int H = h > 0 ? h : targetPx;
        while (W / scale > targetPx || H / scale > targetPx) scale *= 2;
        if (scale == 1) {
            return decodeFull.call();
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = scale;
        o2.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap full = decodeFull.call();
        if (full == null) return null;
        Bitmap out = Bitmap.createScaledBitmap(full,
                Math.max(1, W / scale), Math.max(1, H / scale), true);
        if (out != full) full.recycle();
        return out;
    }

    private void openViewer(List<File> files, int position) {
        if (files == null || files.isEmpty()) return;
        int p = Math.max(0, Math.min(files.size() - 1, position));
        ArrayList<String> paths = new ArrayList<>();
        for (File f : files) paths.add(f.getAbsolutePath());
        Intent i = new Intent(this, ImageViewerActivity.class);
        i.putStringArrayListExtra(ImageViewerActivity.KEY_FILES, paths);
        i.putExtra(ImageViewerActivity.KEY_INDEX, p);
        try {
            startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "无法打开图片",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 3-column grid of all media — retained for callers that want a "gallery view"
     *  dialog; the pool-list now shows every thumbnail inline (no "more" badge). */
    private void showAllMedia(final Experience e) {
        final List<File> files = new ArrayList<>();
        for (int i = 0; i < e.media.size(); i++) {
            File f = store.mediaFile(e, i);
            if (f != null) files.add(f);
        }
        if (files.isEmpty()) return;
        GridView gv = new GridView(this);
        gv.setNumColumns(3);
        gv.setVerticalSpacing(dp(4));
        gv.setHorizontalSpacing(dp(4));
        gv.setPadding(dp(8), dp(8), dp(8), dp(8));
        final List<File> fv = files;
        gv.setAdapter(new android.widget.BaseAdapter() {
            @Override
            public int getCount() { return fv.size(); }
            @Override
            public Object getItem(int p) { return fv.get(p); }
            @Override
            public long getItemId(int p) { return p; }
            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                ImageView iv = cv instanceof ImageView ? (ImageView) cv
                        : new ImageView(ExperiencePoolActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayoutParams(new GridView.LayoutParams(dp(100), dp(100)));
                Bitmap b = decodeSampled(fv.get(p).getAbsolutePath(), dp(150));
                if (b != null) iv.setImageBitmap(b);
                return iv;
            }
        });
        gv.setOnItemClickListener((parent, v, pos, id) -> openViewer(fv, pos));
        new AlertDialog.Builder(this)
                .setTitle(e.title)
                .setView(gv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private String formatTime(long ts) {
        if (ts <= 0) return "未知";
        long diff = System.currentTimeMillis() - ts;
        long mins = diff / 60000;
        if (mins < 1) return "刚刚";
        if (mins < 60) return mins + "分钟";
        long hours = mins / 60;
        if (hours < 24) return hours + "小时";
        long days = hours / 24;
        return days + "天";
    }

    private void showExperienceMenu(final Experience e) {
        String[] items = {"✏️ 编辑", "⭐ 标记重要", "🗑️ 删除"};
        new AlertDialog.Builder(this)
                .setTitle(e.title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            Intent ei = new Intent(this, EditExperienceActivity.class);
                            ei.putExtra(EditExperienceActivity.EXTRA_EXP_ID, e.id);
                            startActivityForResult(ei, 100);
                            break;
                        case 1:
                            store.setImportance(e.id, 1.0);
                            Toast.makeText(this, "已标记为重要", Toast.LENGTH_SHORT).show();
                            refresh();
                            break;
                        case 2:
                            new AlertDialog.Builder(this)
                                    .setTitle("删除经验")
                                    .setMessage("删除「" + e.title + "」及其媒体？")
                                    .setPositiveButton("删除", (d, w) -> {
                                        store.remove(e.id);
                                        refresh();
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                            break;
                    }
                })
                .show();
    }

    private class PoolAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_exp, parent, false);
            }
            final Experience e = items.get(position);
            ((TextView) v.findViewById(R.id.tvExpAgent)).setText(e.agentName);
            ((TextView) v.findViewById(R.id.tvExpType)).setText("user".equals(e.type) ? "用户发布" : "Agent");

            // Enhanced v3 metadata display
            TextView tvExpMeta = (TextView) v.findViewById(R.id.tvExpMeta);
            if (tvExpMeta == null) {
                tvExpMeta = new TextView(ExperiencePoolActivity.this);
                tvExpMeta.setId(R.id.tvExpMeta);
                tvExpMeta.setTextSize(12);
                tvExpMeta.setTextColor(0xFF888888);
                tvExpMeta.setPadding(dp(8), 0, dp(8), 0);
                LinearLayout contentLayout = (LinearLayout) v.findViewById(R.id.llExpContent);
                if (contentLayout != null) contentLayout.addView(tvExpMeta);
            }
            String tagLabel = "auto".equals(e.type) ? "[自动] " : ("user".equals(e.type) ? "[用户] " : "");
            String editLabel = e.userEdited ? "[已编辑] " : "";
            String meta = String.format("%s%s 重要性:%.1f · 被引:%d次 · %s前",
                    tagLabel, editLabel, e.importance, e.useCount,
                    formatTime(e.lastUsedTime > 0 ? e.lastUsedTime : e.time));
            tvExpMeta.setText(meta);

            String text = (e.content == null || e.content.isEmpty()) ? e.title : e.content;
            ((TextView) v.findViewById(R.id.tvExpContent)).setText(text);

            v.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showExperienceMenu(e);
                    return true;
                }
            });

            Button del = (Button) v.findViewById(R.id.btnExpDelete);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    new AlertDialog.Builder(ExperiencePoolActivity.this)
                            .setTitle("删除经验")
                            .setMessage("删除「" + e.title + "」及其媒体？")
                            .setPositiveButton("删除", (d, w) -> {
                                store.remove(e.id);
                                refresh();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });

            LinearLayout grid = (LinearLayout) v.findViewById(R.id.llExpShots);
            buildGrid(grid, e);
            return v;
        }

        private void buildGrid(final LinearLayout parent, final Experience e) {
            parent.removeAllViews();
            final List<File> files = new ArrayList<>();
            // no artificial limit: show every thumbnail inline (req 4: "more" has no meaning)
            for (int i = 0; i < e.media.size(); i++) {
                File f = store.mediaFile(e, i);
                if (f != null) files.add(f);
            }
            if (files.isEmpty()) return;
            int total = files.size();
            int cell = dp(72);
            int m = dp(4);
            for (int r = 0; r * 3 < total; r++) {
                LinearLayout row = new LinearLayout(ExperiencePoolActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = m;
                row.setLayoutParams(rlp);
                for (int c = 0; c < 3; c++) {
                    int idx = r * 3 + c;
                    if (idx >= total) break;
                    ImageView iv = new ImageView(ExperiencePoolActivity.this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(cell, cell);
                    ilp.rightMargin = m;
                    iv.setLayoutParams(ilp);
                    Bitmap b = decodeSampled(files.get(idx).getAbsolutePath(), cell * 2);
                    if (b != null) iv.setImageBitmap(b);
                    final int fi = idx;
                    iv.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                openViewer(files, fi);
                            } catch (Throwable t) {
                                android.widget.Toast.makeText(ExperiencePoolActivity.this,
                                        "无法打开图片", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                    row.addView(iv);
                }
                parent.addView(row);
            }
        }
    }
}
