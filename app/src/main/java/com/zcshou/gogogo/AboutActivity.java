package com.zcshou.gogogo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.elvishew.xlog.XLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 开源说明页：展示打赏码、项目仓库地址与 README 全文。
 * README 内容来自 assets/README.md，用户修改仓库中的 README 后重新编译即可同步更新。
 */
public class AboutActivity extends AppCompatActivity {

    /** 项目仓库地址 */
    public static final String REPO_URL = "https://github.com/1437649480/yuepaoying";

    private static final String ASSET_README = "README.md";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.about_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // 收款码：固定宽高比，避免被拉伸变形
        ImageView qr = findViewById(R.id.about_donate_qr);
        qr.setVisibility(View.VISIBLE);
        qr.setAdjustViewBounds(true);
        findViewById(R.id.about_donate_qr_text).setVisibility(View.VISIBLE);

        // 仓库地址：填了真实地址才可点击跳转；
        // 仍是占位符时只显示提示文字，避免用户点到无效链接。
        TextView repoUrl = findViewById(R.id.about_repo_url);
        View repoBox = findViewById(R.id.about_repo_box);
        if (REPO_URL.contains("YOUR_NAME")) {
            repoUrl.setAutoLinkMask(0);
            repoUrl.setText(R.string.about_repo_placeholder);
        } else {
            repoUrl.setText(REPO_URL);
            repoBox.setOnClickListener(v -> openUrl(REPO_URL));
        }

        // README 全文
        TextView readme = findViewById(R.id.about_readme);
        readme.setText(loadReadme());
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            XLog.e("ERROR: cannot open url");
        }
    }

    /**
     * 读取 assets 中的 README.md。
     * README 是给 GitHub 渲染的，里面混有 markdown 与少量 HTML，
     * 这里做一次轻量清洗，避免把这些原始标记直接显示给用户看。
     * 读取失败时给出简短提示，不影响其余功能。
     */
    private String loadReadme() {
        try (InputStream is = getAssets().open(ASSET_README)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return normalizeMarkdown(text);
        } catch (Exception e) {
            XLog.e("ERROR: read README failed");
            return getResources().getString(R.string.about_readme_failed);
        }
    }

    /**
     * 把 markdown / HTML 整理成便于纯文本阅读的形式。
     * 注意：只清理已知的 HTML 标签名，以免误删 README 里的 &lt;https://...&gt; 自动链接。
     */
    private String normalizeMarkdown(String text) {
        // markdown 图片：![alt](url) -> [图片]
        String s = text.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "[图片]");
        // HTML 图片：<img src="..." ...> -> [图片]
        s = s.replaceAll("(?i)<img\\b[^>]*>", "[图片]");
        // 其余已知 HTML 标签直接去掉（白名单方式，避免吃掉 <https://xxx> 这类自动链接）
        s = s.replaceAll("(?i)</?(p|br|sub|sup|div|span|b|i|u|em|strong|center|font|small|"
                + "h[1-6]|table|thead|tbody|tr|td|th|ul|ol|li|blockquote|details|summary)"
                + "(\\s[^>]*)?/?>", "");

        // markdown 表格：丢掉分隔行，并把竖线换成空格，否则会显示成一大串 "| 类别 | 内容 |"
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("|") && t.endsWith("|")) {
                if (t.matches("\\|[\\s\\-:|]+\\|")) {
                    continue; // 表头下的 |----|----| 分隔行
                }
                t = t.substring(1, t.length() - 1).replace("|", "  ").trim();
            }
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}