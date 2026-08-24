package com.akasha.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 朋友圈-style experience publisher (req 1): text + optional images/video.
 * Media is copied into app-internal storage; the entry is stored in the DB
 * and searchable by any agent with read permission.
 */
public class PublishExperienceActivity extends Activity {

    public static final String EXTRA_POOL_ID = "pool_id";
    private static final int REQ_PICK = 1001;

    private EditText etTitle, etContent;
    private LinearLayout llPreview;
    private final List<Uri> picked = new ArrayList<>();
    private String targetPoolId = PoolInfo.GLOBAL_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_publish_exp);
        etTitle = (EditText) findViewById(R.id.etPubTitle);
        etContent = (EditText) findViewById(R.id.etPubContent);
        llPreview = (LinearLayout) findViewById(R.id.llPubPreview);
        Intent i = getIntent();
        if (i != null) {
            String s = i.getStringExtra(EXTRA_POOL_ID);
            if (s != null && !s.isEmpty()) targetPoolId = s;
        }

        findViewById(R.id.btnPubCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btnPubSend).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publish();
            }
        });

        findViewById(R.id.btnPubAddMedia).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // No count / file-size / mime / video-length limits (req 4).
                // User has explicitly asked us to lift these caps; we apply
                // sampled decode on previews so dozens of 4K+ images do not OOM.
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_PICK);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) {
                    picked.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            rebuildPreview();
        }
    }

    private void rebuildPreview() {
        llPreview.removeAllViews();
        int px = (int) (90 * getResources().getDisplayMetrics().density);
        for (final Uri u : picked) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
            lp.rightMargin = 8;
            iv.setLayoutParams(lp);
            try {
                Bitmap b = ExperiencePoolActivity.decodeSampled(
                        getContentResolver().openInputStream(u), px * 2);
                if (b != null) iv.setImageBitmap(b);
            } catch (Exception ignored) {}
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    picked.remove(u);
                    rebuildPreview();
                }
            });
            llPreview.addView(iv);
        }
    }

    private void publish() {
        String title = etTitle.getText().toString().trim();
        String content = etContent.getText().toString().trim();
        if (title.isEmpty() && content.isEmpty() && picked.isEmpty()) {
            Toast.makeText(this, "写点什么或选个图片/视频吧", Toast.LENGTH_SHORT).show();
            return;
        }
        if (title.isEmpty()) {
            title = content.length() > 20 ? content.substring(0, 20) + "…" : content;
            if (title.isEmpty()) title = "（无标题）";
        }
        ExpStore store = new ExpStore(this);
        Experience e = store.publishUser(title, content, picked);
        // publishUser 已把经验挂到 GLOBAL；如果当前目标池不是 GLOBAL，再挂一次目标池
        if (!PoolInfo.GLOBAL_ID.equals(targetPoolId)) {
            store.linkExpToExtraPool(e.id, targetPoolId);
            // 提示用户：已经同步到全局+目标池
            Toast.makeText(this, "已发布（同步到「全局经验池」+ 当前池）", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已发布" + (e.media.isEmpty() ? "" : "（含" + e.media.size() + "个媒体）"),
                    Toast.LENGTH_SHORT).show();
        }
        setResult(RESULT_OK);
        finish();
    }
}
