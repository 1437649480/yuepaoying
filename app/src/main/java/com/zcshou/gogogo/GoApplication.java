package com.zcshou.gogogo;

import android.app.Application;

import com.baidu.location.LocationClient;
import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.SDKInitializer;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.ChangelessFileNameGenerator;

import java.io.File;

public class GoApplication extends Application {
    public static final String APP_NAME = "GoGoGo";
    public static final String LOG_FILE_NAME = APP_NAME + ".log";
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 3; // 3 days

    @Override
    public void onCreate() {
        super.onCreate();

        initXlog();

        // 百度地图 7.5 开始，要求必须同意隐私政策，默认为false
        SDKInitializer.setAgreePrivacy(this, true);
        // 百度定位 7.5 开始，要求必须同意隐私政策，默认为false(官方说可以统一为以上接口，但实际测试并不行，定位还是需要单独设置)
        LocationClient.setAgreePrivacy(true);
        // 优先使用设置页保存的地图 KEY（需重启生效），否则回退到编译时内置的 KEY。
        // 注意：历史版本曾把中文提示语当作 defaultValue 写入 SharedPreferences，
        // 这里额外做一次校验，避免把提示文字当成 AK 传给 SDK 导致鉴权失败。
        android.content.SharedPreferences sp = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this);
        // 清理历史版本误写入的非法值（例如中文提示语），避免设置页长期显示脏数据
        String storedAk = sp.getString("setting_map_key", null);
        if (storedAk != null && !isUsableAk(storedAk)) {
            sp.edit().remove("setting_map_key").apply();
            storedAk = null;
        }
        String mapAk = pickValidAk(storedAk, BuildConfig.MAPS_API_KEY);
        SDKInitializer.setApiKey(mapAk);
        // 在使用 SDK 各组间之前初始化 context 信息，传入 ApplicationContext
        SDKInitializer.initialize(this);

        SDKInitializer.setCoordType(CoordType.BD09LL);
    }

    /**
     * 从候选值中挑一个可用的 AK。
     * 规则：非空、不是中文提示文字（百度 AK 是纯 ASCII 字母数字），优先取候选值，否则用内置 AK。
     */
    public static String pickValidAk(String candidate, String builtin) {
        if (isUsableAk(candidate)) {
            return candidate.trim();
        }
        return isUsableAk(builtin) ? builtin.trim() : "";
    }

    private static boolean isUsableAk(String ak) {
        if (ak == null) {
            return false;
        }
        String s = ak.trim();
        if (s.isEmpty()) {
            return false;
        }
        // 百度 AK 只由 ASCII 字母和数字组成，出现任何非 ASCII 字符即视为提示文字
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Initialize XLog.
     */
    private void initXlog() {
        File logPath = getExternalFilesDir("Logs");
        if (logPath != null) {
            LogConfiguration config = new LogConfiguration.Builder()
                    .logLevel(LogLevel.ALL)
                    .tag(APP_NAME)                                         // 指定 TAG，默认为 "X-LOG"
                    .enableThreadInfo()                                    // 允许打印线程信息，默认禁止
                    .enableStackTrace(2)                                   // 允许打印深度为 2 的调用栈信息，默认禁止
                    .enableBorder()                                        // 允许打印日志边框，默认禁止
                    .build();

            Printer consolePrinter = new ConsolePrinter();                  // 通过 System.out 打印日志到控制台的打印器
            Printer filePrinter = new FilePrinter                           // 打印日志到文件的打印器
                    .Builder(logPath.getPath())                             // 指定保存日志文件的路径
                    .fileNameGenerator(new ChangelessFileNameGenerator(LOG_FILE_NAME))         // 指定日志文件名生成器，默认为 ChangelessFileNameGenerator("log")
                    .backupStrategy(new NeverBackupStrategy())              // 指定日志文件备份策略，默认为 FileSizeBackupStrategy(1024 * 1024)
                    .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))     // 指定日志文件清除策略，默认为 NeverCleanStrategy()
                    .build();
            XLog.init(config, consolePrinter, filePrinter);
        }
    }
}