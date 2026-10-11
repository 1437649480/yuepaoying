package com.zcshou.gogogo.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.gogogo.joystick.JoyStick;
import com.zcshou.gogogo.utils.GeoUtils;

import java.util.ArrayList;
import java.util.List;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    private NotificationManager mNoteManager;
    // 摇杆相关
    private JoyStick mJoyStick;

    /* ============================ 路线回放 ============================ */

    /** 路线状态广播 */
    public static final String ACTION_ROUTE_STATUS = "com.zcshou.gogogo.ACTION_ROUTE_STATUS";
    public static final String EXTRA_ROUTE_STATE = "EXTRA_ROUTE_STATE";
    public static final String EXTRA_ROUTE_SPEED = "EXTRA_ROUTE_SPEED";
    public static final String EXTRA_ROUTE_TRAVELLED = "EXTRA_ROUTE_TRAVELLED";
    public static final String EXTRA_ROUTE_REMAIN = "EXTRA_ROUTE_REMAIN";
    public static final String EXTRA_ROUTE_ETA = "EXTRA_ROUTE_ETA";
    public static final String EXTRA_ROUTE_MODE = "EXTRA_ROUTE_MODE";
    public static final String EXTRA_ROUTE_NAME = "EXTRA_ROUTE_NAME";
    public static final String EXTRA_ROUTE_LOOP = "EXTRA_ROUTE_LOOP";
    public static final String EXTRA_ROUTE_LNG = "EXTRA_ROUTE_LNG";
    public static final String EXTRA_ROUTE_LAT = "EXTRA_ROUTE_LAT";

    /** 回放模式 */
    public static final int ROUTE_MODE_ONCE = 0;        // 单次
    public static final int ROUTE_MODE_LOOP = 1;        // 循环
    public static final int ROUTE_MODE_ROUNDTRIP = 2;   // 往返

    /** 运行状态 */
    public static final int ROUTE_STATE_IDLE = 0;       // 未运行
    public static final int ROUTE_STATE_RUNNING = 1;    // 运行中
    public static final int ROUTE_STATE_PAUSED = 2;     // 已暂停
    public static final int ROUTE_STATE_FINISHED = 3;   // 已结束

    private List<double[]> mRoutePoints = null;   // 主路线点 [lng, lat]，WGS-84
    private double[] mRouteCum = null;            // 主路线累计距离
    private double mRouteTotal = 0;               // 主路线总长（米）
    private List<double[]> mApproachPoints = null;// 引导段：当前位置 -> 起点（仅走一次）
    private double[] mApproachCum = null;
    private double mApproachTotal = 0;
    private double mRouteSpeed = 1.2;             // 路线速度 m/s
    private int mRouteMode = ROUTE_MODE_ONCE;
    private int mRouteState = ROUTE_STATE_IDLE;
    private double mRouteTravelled = 0;           // 已沿路线行进的总距离（米）
    private long mRouteLastMillis = 0;            // 上一次推进的系统时间
    private int mRouteLoop = 0;                   // 已完成圈数
    private String mRouteName = "";
    private long mLastBroadcastMillis = 0;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        initGoLocation();

        initNotification();

        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
        mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
        mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);

        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        stopRouteInternal();
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        mNoteManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (mNoteManager != null) {
            mNoteManager.createNotificationChannel(mChannel);
        }

        startForeground(SERVICE_GO_NOTE_ID, buildNotification(getResources().getString(R.string.app_service_tips)));
    }

    private Notification buildNotification(String text) {
        //准备intent
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    private void updateNotification(String text) {
        if (mNoteManager != null) {
            try {
                mNoteManager.notify(SERVICE_GO_NOTE_ID, buildNotification(text));
            } catch (Exception e) {
                XLog.e("SERVICEGO: ERROR - updateNotification");
            }
        }
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                // 路线回放期间由路线接管位置，忽略摇杆输入
                if (mRouteState == ROUTE_STATE_RUNNING) {
                    return;
                }
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                if (mRouteState == ROUTE_STATE_RUNNING) {
                    return;
                }
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
            }
        });
        mJoyStick.show();
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        // 路线回放推进（位于写入之前，保证写入的是最新位置）
                        advanceRoute();

                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    /* ============================ 路线回放实现 ============================ */

    /** 构建各点的累计距离数组 */
    private double[] buildCumulative(List<double[]> pts) {
        double[] cum = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            cum[i] = cum[i - 1] + GeoUtils.distance(
                    pts.get(i - 1)[1], pts.get(i - 1)[0],
                    pts.get(i)[1], pts.get(i)[0]);
        }
        return cum;
    }

    /** 按沿路径距离 d 插值出经纬度与方向，写入当前位置 */
    private void applyPosition(List<double[]> pts, double[] cum, double d, boolean reverse) {
        if (pts == null || cum == null || pts.isEmpty()) {
            return;
        }
        if (pts.size() == 1) {
            mCurLng = pts.get(0)[0];
            mCurLat = pts.get(0)[1];
            return;
        }
        double total = cum[cum.length - 1];
        int idx = 1;
        while (idx < cum.length - 1 && cum[idx] < d) {
            idx++;
        }
        if (d <= 0) {
            mCurLng = pts.get(0)[0];
            mCurLat = pts.get(0)[1];
        } else if (d >= total) {
            mCurLng = pts.get(pts.size() - 1)[0];
            mCurLat = pts.get(pts.size() - 1)[1];
        } else {
            double segLen = cum[idx] - cum[idx - 1];
            double t = segLen > 0 ? (d - cum[idx - 1]) / segLen : 0;
            mCurLat = pts.get(idx - 1)[1] + (pts.get(idx)[1] - pts.get(idx - 1)[1]) * t;
            mCurLng = pts.get(idx - 1)[0] + (pts.get(idx)[0] - pts.get(idx - 1)[0]) * t;
        }
        double brg = GeoUtils.bearing(pts.get(idx - 1)[1], pts.get(idx - 1)[0],
                pts.get(idx)[1], pts.get(idx)[0]);
        if (reverse) {
            brg = (brg + 180) % 360;
        }
        mCurBea = (float) brg;
        mSpeed = mRouteSpeed;
    }

    /** 每 100ms 由定位循环调用一次，推进路线 */
    private void advanceRoute() {
        if (mRouteState != ROUTE_STATE_RUNNING) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (mRouteLastMillis <= 0) {
            mRouteLastMillis = now;
        }
        double dt = (now - mRouteLastMillis) / 1000.0;
        mRouteLastMillis = now;
        // 异常时间跳变（息屏 / 长时间挂起）时做保护，避免瞬间瞬移
        if (dt < 0 || dt > 5) {
            dt = 0.1;
        }
        mRouteTravelled += mRouteSpeed * dt;

        double d = mRouteTravelled;

        // 1) 引导段（当前位置 -> 起点），只走一次
        if (mApproachPoints != null && mApproachTotal > 0) {
            if (d < mApproachTotal) {
                applyPosition(mApproachPoints, mApproachCum, d, false);
                broadcastRouteStatus();
                return;
            }
            d -= mApproachTotal;
        }

        if (mRouteTotal <= 0) {
            finishRouteInternal();
            return;
        }

        // 2) 主路线：按模式映射进度
        boolean reverse = false;
        switch (mRouteMode) {
            case ROUTE_MODE_LOOP:
                if (d >= mRouteTotal) {
                    mRouteLoop = (int) (d / mRouteTotal);
                    d = d % mRouteTotal;
                }
                break;
            case ROUTE_MODE_ROUNDTRIP: {
                double period = mRouteTotal * 2;
                if (d >= period) {
                    mRouteLoop = (int) (d / period);
                }
                double p = d % period;
                if (p <= mRouteTotal) {
                    d = p;
                    reverse = false;
                } else {
                    d = period - p;
                    reverse = true;
                }
                break;
            }
            case ROUTE_MODE_ONCE:
            default:
                if (d >= mRouteTotal) {
                    applyPosition(mRoutePoints, mRouteCum, mRouteTotal, false);
                    finishRouteInternal();
                    return;
                }
                break;
        }

        applyPosition(mRoutePoints, mRouteCum, d, reverse);
        broadcastRouteStatus();
    }

    /** 计算当前圈剩余距离 */
    private double currentRemain() {
        double d = mRouteTravelled;
        if (mApproachPoints != null && mApproachTotal > 0) {
            d -= mApproachTotal;
        }
        if (d < 0) {
            d = 0;
        }
        if (mRouteTotal <= 0) {
            return 0;
        }
        switch (mRouteMode) {
            case ROUTE_MODE_LOOP:
                return mRouteTotal - (d % mRouteTotal);
            case ROUTE_MODE_ROUNDTRIP: {
                double period = mRouteTotal * 2;
                double p = d % period;
                return period - p;
            }
            case ROUTE_MODE_ONCE:
            default:
                return Math.max(0, mRouteTotal - d);
        }
    }

    private void broadcastRouteStatus() {
        long now = System.currentTimeMillis();
        if (now - mLastBroadcastMillis < 500 && mRouteState == ROUTE_STATE_RUNNING) {
            return;
        }
        mLastBroadcastMillis = now;

        double remain = currentRemain();
        double eta = mRouteSpeed > 0 ? remain / mRouteSpeed : 0;

        Intent intent = new Intent(ACTION_ROUTE_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_ROUTE_STATE, mRouteState);
        intent.putExtra(EXTRA_ROUTE_SPEED, mRouteSpeed);
        intent.putExtra(EXTRA_ROUTE_TRAVELLED, mRouteTravelled);
        intent.putExtra(EXTRA_ROUTE_REMAIN, remain);
        intent.putExtra(EXTRA_ROUTE_ETA, eta);
        intent.putExtra(EXTRA_ROUTE_MODE, mRouteMode);
        intent.putExtra(EXTRA_ROUTE_NAME, mRouteName);
        intent.putExtra(EXTRA_ROUTE_LOOP, mRouteLoop);
        intent.putExtra(EXTRA_ROUTE_LNG, mCurLng);
        intent.putExtra(EXTRA_ROUTE_LAT, mCurLat);
        sendBroadcast(intent);

        // 同步摇杆显示位置
        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
    }

    private void finishRouteInternal() {
        mRouteState = ROUTE_STATE_FINISHED;
        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        mLastBroadcastMillis = 0;
        broadcastRouteStatus();
        updateNotification(getResources().getString(R.string.app_service_tips));
        XLog.d("SERVICEGO: route finished");
    }

    private void stopRouteInternal() {
        mRouteState = ROUTE_STATE_IDLE;
        mRoutePoints = null;
        mRouteCum = null;
        mRouteTotal = 0;
        mApproachPoints = null;
        mApproachCum = null;
        mApproachTotal = 0;
        mRouteTravelled = 0;
        mRouteLoop = 0;
        mRouteLastMillis = 0;
        mRouteName = "";
    }

    /* ============================ 广播接收 ============================ */

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }

    /* ============================ Binder ============================ */

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            // 手动传送时结束正在进行的路线回放
            if (mRouteState == ROUTE_STATE_RUNNING || mRouteState == ROUTE_STATE_PAUSED) {
                stopRouteInternal();
            }
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }

        /** 当前位置（WGS-84） */
        public double[] getPosition() {
            return new double[]{mCurLng, mCurLat, mCurAlt};
        }

        /**
         * 开始路线回放
         *
         * @param points      路线点 [lng, lat]，WGS-84
         * @param speed       速度，米/秒
         * @param mode        ROUTE_MODE_*
         * @param jumpToStart true 直接跳到起点；false 从当前位置按速度移动到起点
         * @param name        路线名称（仅用于展示）
         */
        public void startRoute(List<double[]> points, double speed, int mode, boolean jumpToStart, String name) {
            if (points == null || points.size() < 2) {
                XLog.e("SERVICEGO: startRoute with invalid points");
                return;
            }
            mRoutePoints = new ArrayList<>(points);
            mRouteCum = buildCumulative(mRoutePoints);
            mRouteTotal = mRouteCum[mRouteCum.length - 1];
            mRouteSpeed = speed;
            mRouteMode = mode;
            mRouteName = name == null ? "" : name;
            mRouteTravelled = 0;
            mRouteLoop = 0;
            mRouteLastMillis = 0;
            mLastBroadcastMillis = 0;

            if (jumpToStart) {
                mApproachPoints = null;
                mApproachCum = null;
                mApproachTotal = 0;
                mCurLng = mRoutePoints.get(0)[0];
                mCurLat = mRoutePoints.get(0)[1];
            } else {
                // 引导段：当前位置 -> 起点
                mApproachPoints = new ArrayList<>();
                mApproachPoints.add(new double[]{mCurLng, mCurLat});
                mApproachPoints.add(new double[]{mRoutePoints.get(0)[0], mRoutePoints.get(0)[1]});
                mApproachCum = buildCumulative(mApproachPoints);
                mApproachTotal = mApproachCum[mApproachCum.length - 1];
                // 起终点几乎重合时无需引导
                if (mApproachTotal < 1) {
                    mApproachPoints = null;
                    mApproachCum = null;
                    mApproachTotal = 0;
                }
            }

            mRouteState = ROUTE_STATE_RUNNING;
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            updateNotification(getResources().getString(R.string.route_running_notify));
            broadcastRouteStatus();
            XLog.d("SERVICEGO: route started, points=" + mRoutePoints.size() + " len=" + mRouteTotal);
        }

        public void pauseRoute() {
            if (mRouteState == ROUTE_STATE_RUNNING) {
                mRouteState = ROUTE_STATE_PAUSED;
                broadcastRouteStatus();
                updateNotification(getResources().getString(R.string.route_paused_notify));
            }
        }

        public void resumeRoute() {
            if (mRouteState == ROUTE_STATE_PAUSED) {
                mRouteState = ROUTE_STATE_RUNNING;
                mRouteLastMillis = 0;
                broadcastRouteStatus();
                updateNotification(getResources().getString(R.string.route_running_notify));
            }
        }

        public void stopRoute() {
            stopRouteInternal();
            mLastBroadcastMillis = 0;
            broadcastRouteStatus();
            updateNotification(getResources().getString(R.string.app_service_tips));
        }

        public void setRouteSpeed(double speed) {
            if (speed > 0) {
                mRouteSpeed = speed;
                broadcastRouteStatus();
            }
        }

        public void setRouteMode(int mode) {
            mRouteMode = mode;
            broadcastRouteStatus();
        }

        public int getRouteState() {
            return mRouteState;
        }

        public double getRouteSpeed() {
            return mRouteSpeed;
        }

        public int getRouteMode() {
            return mRouteMode;
        }

        public String getRouteName() {
            return mRouteName;
        }
    }
}
