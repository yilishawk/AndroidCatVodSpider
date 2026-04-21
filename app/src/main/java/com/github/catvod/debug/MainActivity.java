package com.github.catvod.debug;

import android.app.Activity;
import android.os.Bundle;
import com.github.catvod.R;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.spider.*;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private ExecutorService executor;
    private Spider spider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        executor = Executors.newCachedThreadPool();
        executor.execute(this::initSpider);

        findViewById(R.id.detailContent).setOnClickListener(view -> executor.execute(() -> {
            try {
                // 模擬殼子傳入的 ID
                spider.detailContent(Arrays.asList("/voddetail/12345.html"));
            } catch (Exception e) { SpiderDebug.log(e); }
        }));
    }

    private void initSpider() {
        try {
            Init.init(getApplicationContext());
            spider = new KaiGe();
            // 在這裡動態傳入配置，KaiGe 內部就不需要寫死網址了
            String config = "{\"host\":\"https://api.example.com\",\"site_name\":\"測試站\"}";
            spider.init(this, config);
            
            // 獲取日誌訪問地址
            String logUrl = Proxy.getUrl() + "?do=kaige_log";
            SpiderDebug.log("====================================");
            SpiderDebug.log("🚀 日誌系統已就緒！");
            SpiderDebug.log("👉 請訪問: " + logUrl);
            SpiderDebug.log("====================================");
        } catch (Throwable e) { SpiderDebug.log(e); }
    }
}
