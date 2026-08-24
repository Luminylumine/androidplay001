package com.akasha.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class EditExperienceActivity extends Activity {
    
    public static final String EXTRA_EXP_ID = "exp_id";
    private String expId;
    private ExpStore store;
    private Experience exp;
    private EditText etTitle, etContent;
    private TextView tvMeta;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_experience);
        
        expId = getIntent().getStringExtra(EXTRA_EXP_ID);
        store = new ExpStore(this);
        exp = store.getById(expId);
        
        if (exp == null) {
            Toast.makeText(this, "经验不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        etTitle = (EditText) findViewById(R.id.etEditExpTitle);
        etContent = (EditText) findViewById(R.id.etEditExpContent);
        tvMeta = (TextView) findViewById(R.id.tvEditExpMeta);
        
        etTitle.setText(exp.title);
        etContent.setText(exp.content);
        
        String tagLabel = "auto".equals(exp.type) ? "[自动]" : ("user".equals(exp.type) ? "[用户]" : "");
        tvMeta.setText(String.format("来源: %s · 重要性: %.1f · 被引: %d次",
                tagLabel, exp.importance, exp.useCount));
        
        Button btnSave = (Button) findViewById(R.id.btnEditExpSave);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String title = etTitle.getText().toString().trim();
                String content = etContent.getText().toString().trim();
                if (title.isEmpty()) {
                    Toast.makeText(EditExperienceActivity.this, "标题不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                store.update(expId, title, content);
                Toast.makeText(EditExperienceActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        
        Button btnCancel = (Button) findViewById(R.id.btnEditExpCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
    }
}