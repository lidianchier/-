package com.example.xjtuclassroom;

import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    // 状态控制
    private boolean isRunningTask = false;
    private boolean isTaskPending = false;

    private String targetDate = "";
    private String targetCampus = "1";
    private int currentPeriod = 1;

    // 📝 日志缓存池 (用于回传给 React)
    private List<String> executionLogs = new ArrayList<>();

    // 临时数据缓冲
    private JSONObject sessionBuffer = new JSONObject();

    // URL 常量
    private static final String LOGIN_URL = "https://login.xjtu.edu.cn/cas/login";
    private static final String TARGET_URL = "https://ehall.xjtu.edu.cn/jwapp/sys/kxjas/*default/index.do?#/kxjscx";
    private static final String LOCAL_URL = "file:///android_asset/index.html";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.loadUrl(LOGIN_URL);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // React 界面忽略
                if (url.startsWith("file:///")) return;

                String cookies = CookieManager.getInstance().getCookie(url);

                if (url.contains("login.xjtu.edu.cn")) return;

                // 强力纠偏
                if ((url.contains("ywtb.xjtu.edu.cn") || url.contains("new/index.html")) && !url.contains("kxjas")) {
                    Log.d("XJTU", "跳转纠偏...");
                    view.loadUrl(TARGET_URL);
                    return;
                }

                if (cookies != null && cookies.contains("JSESSIONID")) {
                    if (url.contains("jwapp/sys/kxjas")) {
                        if (isTaskPending && !isRunningTask) {
                            isTaskPending = false;
                            isRunningTask = true;

                            // 注入增强版控制台
                            injectOverlayUI(view);
                            logToOverlay("🚀 页面加载完毕，准备开始抓取任务...", "info");

                            new android.os.Handler().postDelayed(() ->
                                    fetchPeriodOnRemotePage(targetDate, targetCampus, currentPeriod), 2000);
                        }
                        else if (!isTaskPending && !isRunningTask) {
                            view.loadUrl(LOCAL_URL);
                        }
                    }
                }
            }
        });
    }

    // 💉 注入增强版悬浮控制台 (带停止按钮)
    private void injectOverlayUI(WebView view) {
        String jsOverlay = "javascript:(function() {" +
                "   if(document.getElementById('app-overlay')) return;" +
                "   var div = document.createElement('div');" +
                "   div.id = 'app-overlay';" +
                "   div.style = 'position:fixed; bottom:0; left:0; right:0; height:50vh; background:rgba(0,0,0,0.95); color:#0f0; z-index:99999; padding:12px; font-family:monospace; font-size:11px; overflow-y:scroll; word-break:break-all; box-shadow:0 -4px 20px rgba(0,0,0,0.5); border-top: 2px solid #00ff00;';" +
                "   div.innerHTML = '<div style=\"display:flex;justify-content:space-between;align-items:center;border-bottom:1px dashed #333;padding-bottom:8px;margin-bottom:8px;\"><span style=\"font-weight:bold;font-size:14px;color:yellow\">⚡️ 正在同步数据...</span><span id=\"progress-text\" style=\"color:white\">初始化</span></div><div id=\"app-logs\"></div>';" +
                "   document.body.appendChild(div);" +
                "   " +
                "   var btn = document.createElement('button');" +
                "   btn.innerHTML = '■ 停止任务';" +
                "   btn.style = 'position:fixed; top:15%; right:15px; z-index:100000; background:#ff4444; color:white; padding:12px 20px; border:none; font-weight:bold; border-radius:8px; box-shadow:0 4px 15px rgba(255,0,0,0.4); font-size:14px; letter-spacing:1px;';" +
                "   btn.onclick = function(){ " +
                "       this.innerHTML = \"正在停止...\";" +
                "       this.style.background = \"#666\";" +
                "       window.AndroidBridge.forceReturn(); " +
                "   };" +
                "   document.body.appendChild(btn);" +
                "})()";
        view.loadUrl(jsOverlay);
    }

    // 打印日志到悬浮窗 + 缓存
    private void logToOverlay(String msg, String type) {
        // 存入 Java 缓存
        String time = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        executionLogs.add("[" + time + "] " + msg);

        // 构造 HTML 颜色
        String color = "#ccc";
        if (type.equals("success")) color = "#4ade80"; // 绿
        if (type.equals("error")) color = "#ef4444";   // 红
        if (type.equals("info")) color = "#60a5fa";    // 蓝
        if (type.equals("warn")) color = "#fbbf24";    // 黄

        String safeMsg = msg.replace("'", "\\'").replace("\n", "<br>");
        String js = "javascript:(function(){" +
                "   var logDiv = document.getElementById('app-logs');" +
                "   if(logDiv) {" +
                "       var p = document.createElement('div');" +
                "       p.style.padding = '3px 0'; p.style.borderBottom = '1px solid #222'; p.style.color = '" + color + "';" +
                "       p.innerHTML = '<span style=\"opacity:0.5;margin-right:6px\">[" + time + "]</span> " + safeMsg + "';" +
                "       logDiv.appendChild(p);" +
                "       logDiv.parentElement.scrollTop = logDiv.parentElement.scrollHeight;" +
                "   }" +
                "})()";
        runOnUiThread(() -> webView.loadUrl(js));
    }

    private void fetchPeriodOnRemotePage(String date, String campus, int period) {
        logToOverlay("📡 发起请求: " + date + " (校区:" + campus + ") 第" + period + "节", "info");

        // 更新进度文字
        String progressText = period + " / 12";
        String updateProgressJs = "javascript:(function(){ var el=document.getElementById('progress-text'); if(el) el.innerText='" + progressText + "'; })()";
        runOnUiThread(() -> webView.loadUrl(updateProgressJs));

        String jsCode = "javascript:(function() {" +
                "   try {" +
                "       var d = '" + date + "';" +
                "       var c = '" + campus + "';" +
                "       var p = " + period + ";" +
                "       " +
                "       var qList = [" +
                "         {name:'XXXQDM',caption:'学校校区',linkOpt:'AND',builderList:'cbl_m_List',builder:'m_value_equal',value:c}," +
                "         {name:'KXRQ',caption:'空闲日期',linkOpt:'AND',builderList:'cbl_Other',builder:'equal',value:d}," +
                "         {name:'KXJC',caption:'空闲节次',builder:'lessEqual',linkOpt:'AND',builderList:'cbl_Other',value:p}," +
                "         {name:'KXJC',caption:'空闲节次',linkOpt:'AND',builderList:'cbl_String',builder:'moreEqual',value:p}," +
                "         {name:'XXXQDM',value:c,linkOpt:'AND',builder:'equal'}," +
                "         {name:'KXRQ',value:d,linkOpt:'AND',builder:'equal'}," +
                "         {name:'JSJC',value:p,linkOpt:'AND',builder:'equal'}," +
                "         {name:'KSJC',value:p,linkOpt:'AND',builder:'equal'}" +
                "       ];" +
                "       " +
                "       var params = new URLSearchParams();" +
                "       params.append('XXXQDM', c);" +
                "       params.append('KXRQ', d);" +
                "       params.append('JSJC', p);" +
                "       params.append('KSJC', p);" +
                "       params.append('querySetting', JSON.stringify(qList));" +
                "       params.append('pageSize', '300');" +
                "       params.append('pageNumber', '1');" +
                "       " +
                "       fetch('https://ehall.xjtu.edu.cn/jwapp/sys/kxjas/modules/kxjscx/cxkxjs.do', {" +
                "           method: 'POST'," +
                "           headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest'}," +
                "           body: params" +
                "       })" +
                "       .then(res => {" +
                "           if(!res.ok) throw new Error('HTTP ' + res.status);" +
                "           return res.text();" +
                "       })" +
                "       .then(text => {" +
                "           window.AndroidBridge.onResult(" + period + ", text);" +
                "       })" +
                "       .catch(err => {" +
                "           window.AndroidBridge.onError('网络请求失败: ' + err.toString());" +
                "       });" +
                "   } catch(e) {" +
                "       window.AndroidBridge.onError('JS运行错误: ' + e.toString());" +
                "   }" +
                "})()";

        webView.evaluateJavascript(jsCode, null);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void fetchDateSchedule(String date, String campusCode) {
            targetDate = date;
            targetCampus = campusCode;
            currentPeriod = 1;
            isTaskPending = true;
            isRunningTask = false;
            sessionBuffer = new JSONObject();
            executionLogs.clear(); // 清空日志

            runOnUiThread(() -> webView.loadUrl(TARGET_URL));
        }

        @JavascriptInterface
        public void notifyReactReady() {
            runOnUiThread(() -> {
                try {
                    if (sessionBuffer.length() > 0) {
                        JSONObject fullSync = new JSONObject();
                        fullSync.put("type", "FULL_SYNC");
                        fullSync.put("date", targetDate);
                        fullSync.put("campus", targetCampus);
                        fullSync.put("data", sessionBuffer);

                        // 📦 关键：把刚才抓取过程的所有日志打包发给 React
                        fullSync.put("logs", new JSONArray(executionLogs));

                        runJsCallback("window.updateFromAndroid", fullSync.toString());

                        sessionBuffer = new JSONObject();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        @JavascriptInterface
        public void onResult(int period, String jsonStr) {
            try {
                JSONObject root = new JSONObject(jsonStr);
                if(root.has("datas")) {
                    JSONArray rows = root.getJSONObject("datas").getJSONObject("cxkxjs").getJSONArray("rows");
                    logToOverlay("✅ 第" + period + "节: 获取成功，解析到 " + rows.length() + " 间空闲教室", "success");
                    sessionBuffer.put(String.valueOf(period), rows);
                } else {
                    logToOverlay("⚠️ 第" + period + "节: 数据格式异常 (无datas字段)", "warn");
                }
            } catch (Exception e) {
                logToOverlay("❌ 第" + period + "节: JSON 解析失败 - " + e.getMessage(), "error");
            }

            if (period < 12) {
                currentPeriod = period + 1;
                // 延时 500ms 防止过快
                new android.os.Handler().postDelayed(() ->
                        runOnUiThread(() -> fetchPeriodOnRemotePage(targetDate, targetCampus, currentPeriod)), 500);
            } else {
                logToOverlay("🎉 1-12节全部同步完成! 正在生成报表...", "success");
                isTaskPending = false;
                isRunningTask = false;
                new android.os.Handler().postDelayed(() ->
                        runOnUiThread(() -> webView.loadUrl(LOCAL_URL)), 1500);
            }
        }

        @JavascriptInterface
        public void onError(String error) {
            logToOverlay("❌ 错误: " + error, "error");
            // 报错也继续，防止卡死
            if (currentPeriod < 12) {
                currentPeriod++;
                runOnUiThread(() -> fetchPeriodOnRemotePage(targetDate, targetCampus, currentPeriod));
            } else {
                isTaskPending = false;
                isRunningTask = false;
                runOnUiThread(() -> webView.loadUrl(LOCAL_URL));
            }
        }

        @JavascriptInterface
        public void forceReturn() {
            logToOverlay("用户强制停止任务...", "warn");
            isTaskPending = false;
            isRunningTask = false;
            runOnUiThread(() -> webView.loadUrl(LOCAL_URL));
        }

        @JavascriptInterface
        public void relogin() {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            isTaskPending = false;
            isRunningTask = false;
            runOnUiThread(() -> webView.loadUrl(LOGIN_URL));
        }
    }

    private void runJsCallback(String method, String jsonParam) {
        runOnUiThread(() -> {
            String js = "if(window." + method.replace("window.", "") + ") { " + method + "('" + jsonParam.replace("'", "\\'") + "'); }";
            webView.evaluateJavascript(js, null);
        });
    }
}