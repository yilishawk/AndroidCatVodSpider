package com.github.catvod.debug;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.github.catvod.R;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.spider.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private ExecutorService executor;
    private Spider spider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. 初始化線程池
        executor = Executors.newCachedThreadPool();

        // 2. 設置佈局 (如果你沒有 XML，這裏代碼構建了一個簡單界面)
        setContentView(R.layout.activity_main);

        // 3. 異步初始化爬蟲引擎
        executor.execute(() -> {
            try {
                SpiderDebug.log("🚀 正在加載 KaiGe 引擎...");
                // 這裡傳入你的測試配置 JSON
                spider.init(this, "{\"site_name\":\"凱哥調試站\",\"host\":\"https://www.google.com\"}");
                
                String debugUrl = "http://127.0.0.1:" + Proxy.getPort() + "/proxy?do=kaige_debug";
                SpiderDebug.log("✅ 初始化成功！");
                SpiderDebug.log("🌐 實時日誌地址: " + debugUrl);
            } catch (Exception e) {
                SpiderDebug.log(e);
            }
        });

        // 4. 綁定按鈕事件 (對應你 XML 裡的按鈕 ID)
        setupButtons();
    }

    private void setupButtons() {
        // 測試詳情頁解析
        View btnDetail = findViewById(R.id.detailContent);
        if (btnDetail != null) {
            btnDetail.setOnClickListener(v -> executor.execute(() -> {
                try {
                    SpiderDebug.log("🧪 測試詳情頁解析中...");
                    String result = spider.detailContent(Arrays.asList("/test_id_123"));
                    SpiderDebug.log("📥 返回結果: " + result);
                } catch (Exception e) {
                    SpiderDebug.log(e);
                }
            }));
        }

        // 測試搜索解析
        View btnSearch = findViewById(R.id.searchContent);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> executor.execute(() -> {
                try {
                    SpiderDebug.log("🔎 測試搜索功能: 關鍵字 [凱哥]");
                    String result = spider.searchContent("凱哥", false);
                    SpiderDebug.log("📥 搜索結果: " + result);
                } catch (Exception e) {
                    SpiderDebug.log(e);
                }
            }));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
        if (spider != null) spider.destroy();
    }
}
