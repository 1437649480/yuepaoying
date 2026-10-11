package com.zcshou.gogogo;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapPoi;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.database.DataBaseRoute;
import com.zcshou.gogogo.route.RoutePoint;
import com.zcshou.gogogo.route.RouteStore;
import com.zcshou.gogogo.utils.GeoUtils;
import com.zcshou.gogogo.utils.GoUtils;
import com.zcshou.gogogo.utils.MapUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线编辑器。
 *
 * 支持在地图上添加 / 拖动 / 插入 / 删除途经点，并提供撤销（回退笔画）与重做。
 * 内部统一以 WGS-84 保存，地图显示前转换为 BD-09。
 */
public class RouteEditorActivity extends BaseActivity {

    public static final String EXTRA_ROUTE_ID = "EXTRA_ROUTE_ID";
    public static final String EXTRA_ROUTE_NAME = "EXTRA_ROUTE_NAME";
    public static final String EXTRA_CENTER_LNG = "EXTRA_CENTER_LNG";
    public static final String EXTRA_CENTER_LAT = "EXTRA_CENTER_LAT";
    public static final String EXTRA_RESULT_ID = "EXTRA_RESULT_ID";
    public static final long NEW_ROUTE = -1L;

    private static final String KEY_INDEX = "KEY_INDEX";
    private static final int MAX_UNDO = 100;
    /** 默认中心：北京（WGS-84） */
    private static final double DEFAULT_CENTER_LNG = 116.397428;
    private static final double DEFAULT_CENTER_LAT = 39.90923;

    private MapView mMapView;
    private BaiduMap mBaiduMap;
    private TextView mInfoText;

    private List<RoutePoint> mPoints = new ArrayList<>();
    private final List<List<RoutePoint>> mUndoStack = new ArrayList<>();
    private final List<List<RoutePoint>> mRedoStack = new ArrayList<>();

    private long mRouteId = NEW_ROUTE;
    private String mRouteName = "";
    private SQLiteDatabase mRouteDB;
    private boolean mDirty = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_editor);

        Toolbar toolbar = findViewById(R.id.route_editor_toolbar);
        setSupportActionBar(toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }

        try {
            mRouteDB = new DataBaseRoute(getApplicationContext()).getWritableDatabase();
        } catch (Exception e) {
            XLog.e("ERROR: route db init error");
        }

        mInfoText = findViewById(R.id.route_info_text);

        double centerLng = getIntent().getDoubleExtra(EXTRA_CENTER_LNG, DEFAULT_CENTER_LNG);
        double centerLat = getIntent().getDoubleExtra(EXTRA_CENTER_LAT, DEFAULT_CENTER_LAT);

        mRouteId = getIntent().getLongExtra(EXTRA_ROUTE_ID, NEW_ROUTE);
        mRouteName = getIntent().getStringExtra(EXTRA_ROUTE_NAME);

        initMap(centerLng, centerLat);
        initButtons();
        loadRouteIfNeeded();
        refreshOverlays();
    }

    private void initMap(double centerLng, double centerLat) {
        mMapView = findViewById(R.id.route_map_view);
        mMapView.showZoomControls(false);
        mBaiduMap = mMapView.getMap();
        mBaiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL);

        double[] bd = MapUtils.wgs2bd09(centerLng, centerLat);
        MapStatusUpdate status = MapStatusUpdateFactory.newLatLngZoom(new LatLng(bd[1], bd[0]), 16f);
        mBaiduMap.setMapStatus(status);

        mBaiduMap.setOnMapClickListener(new BaiduMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng point) {
                addPoint(point);
            }

            @Override
            public void onMapPoiClick(MapPoi poi) {
                addPoint(poi.getPosition());
            }
        });

        mBaiduMap.setOnMapDoubleClickListener(point -> mBaiduMap.animateMapStatus(MapStatusUpdateFactory.zoomIn()));

        mBaiduMap.setOnMarkerClickListener(marker -> {
            int index = getMarkerIndex(marker);
            if (index >= 0) {
                showPointMenu(index);
            }
            return true;
        });

        mBaiduMap.setOnMarkerDragListener(new BaiduMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(Marker marker) {
                // 记录拖动前的状态，便于撤销
                pushUndo();
            }

            @Override
            public void onMarkerDrag(Marker marker) {
                // 拖动过程中不重绘，避免卡顿
            }

            @Override
            public void onMarkerDragEnd(Marker marker) {
                int index = getMarkerIndex(marker);
                if (index >= 0 && index < mPoints.size()) {
                    LatLng ll = marker.getPosition();
                    double[] wgs = MapUtils.bd2wgs(ll.longitude, ll.latitude);
                    mPoints.get(index).longitude = wgs[0];
                    mPoints.get(index).latitude = wgs[1];
                    mDirty = true;
                }
                mMapView.post(RouteEditorActivity.this::refreshOverlays);
            }
        });
    }

    private void initButtons() {
        ImageButton btnUndo = findViewById(R.id.route_btn_undo);
        ImageButton btnRedo = findViewById(R.id.route_btn_redo);
        ImageButton btnClear = findViewById(R.id.route_btn_clear);
        Button btnSave = findViewById(R.id.route_btn_save);
        ImageButton btnZoomIn = findViewById(R.id.route_zoom_in);
        ImageButton btnZoomOut = findViewById(R.id.route_zoom_out);

        btnUndo.setOnClickListener(v -> doUndo());
        btnRedo.setOnClickListener(v -> doRedo());
        btnClear.setOnClickListener(v -> doClear());
        btnSave.setOnClickListener(v -> doSave());
        btnZoomIn.setOnClickListener(v -> mBaiduMap.animateMapStatus(MapStatusUpdateFactory.zoomIn()));
        btnZoomOut.setOnClickListener(v -> mBaiduMap.animateMapStatus(MapStatusUpdateFactory.zoomOut()));
    }

    private void loadRouteIfNeeded() {
        if (mRouteId <= 0 || mRouteDB == null) {
            return;
        }
        RouteStore.RouteItem item = RouteStore.getById(mRouteDB, mRouteId);
        if (item != null) {
            mPoints = item.points;
            if (!TextUtils.isEmpty(item.name)) {
                mRouteName = item.name;
            }
        }
    }

    /* ============================ 点操作 ============================ */

    private void addPoint(LatLng point) {
        pushUndo();
        double[] wgs = MapUtils.bd2wgs(point.longitude, point.latitude);
        mPoints.add(new RoutePoint(wgs[0], wgs[1]));
        mDirty = true;
        refreshOverlays();
        GoUtils.DisplayToast(this, getString(R.string.route_point_added, mPoints.size()));
    }

    private int getMarkerIndex(Marker marker) {
        if (marker == null || marker.getExtraInfo() == null) {
            return -1;
        }
        return marker.getExtraInfo().getInt(KEY_INDEX, -1);
    }

    private void showPointMenu(final int index) {
        String[] items = new String[]{
                getString(R.string.route_point_insert),
                getString(R.string.route_point_delete),
                getString(R.string.route_point_drag_tips)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.route_editor_title) + " - " + (index + 1))
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            insertAfter(index);
                            break;
                        case 1:
                            deleteAt(index);
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void insertAfter(int index) {
        if (index < 0 || index >= mPoints.size()) {
            return;
        }
        pushUndo();
        RoutePoint a = mPoints.get(index);
        RoutePoint mid;
        if (index + 1 < mPoints.size()) {
            RoutePoint b = mPoints.get(index + 1);
            mid = new RoutePoint((a.longitude + b.longitude) / 2, (a.latitude + b.latitude) / 2);
        } else {
            // 末点：沿最后一段方向延伸 50 米
            RoutePoint prev = index > 0 ? mPoints.get(index - 1) : a;
            double dLng = a.longitude - prev.longitude;
            double dLat = a.latitude - prev.latitude;
            if (Math.abs(dLng) < 1e-9 && Math.abs(dLat) < 1e-9) {
                dLat = GeoUtils.metersToLatDegrees(50);
            } else {
                double len = GeoUtils.distance(prev.latitude, prev.longitude, a.latitude, a.longitude);
                double k = len > 0 ? 50.0 / len : 0;
                dLng *= k;
                dLat *= k;
            }
            mid = new RoutePoint(a.longitude + dLng, a.latitude + dLat);
        }
        mPoints.add(index + 1, mid);
        mDirty = true;
        refreshOverlays();
        GoUtils.DisplayToast(this, getString(R.string.route_point_inserted, index + 1));
    }

    private void deleteAt(int index) {
        if (index < 0 || index >= mPoints.size()) {
            return;
        }
        pushUndo();
        mPoints.remove(index);
        mDirty = true;
        refreshOverlays();
        GoUtils.DisplayToast(this, getString(R.string.route_point_deleted, index + 1));
    }

    /* ============================ 撤销 / 重做 ============================ */

    private List<RoutePoint> copyPoints(List<RoutePoint> src) {
        List<RoutePoint> copy = new ArrayList<>();
        for (RoutePoint p : src) {
            copy.add(p.copy());
        }
        return copy;
    }

    private void pushUndo() {
        mUndoStack.add(copyPoints(mPoints));
        if (mUndoStack.size() > MAX_UNDO) {
            mUndoStack.remove(0);
        }
        mRedoStack.clear();
    }

    private void doUndo() {
        if (mUndoStack.isEmpty()) {
            GoUtils.DisplayToast(this, getString(R.string.route_nothing_undo));
            return;
        }
        mRedoStack.add(copyPoints(mPoints));
        mPoints = mUndoStack.remove(mUndoStack.size() - 1);
        mDirty = true;
        refreshOverlays();
        GoUtils.DisplayToast(this, getString(R.string.route_undo_done));
    }

    private void doRedo() {
        if (mRedoStack.isEmpty()) {
            GoUtils.DisplayToast(this, getString(R.string.route_nothing_redo));
            return;
        }
        mUndoStack.add(copyPoints(mPoints));
        mPoints = mRedoStack.remove(mRedoStack.size() - 1);
        mDirty = true;
        refreshOverlays();
        GoUtils.DisplayToast(this, getString(R.string.route_redo_done));
    }

    private void doClear() {
        if (mPoints.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.route_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    pushUndo();
                    mPoints = new ArrayList<>();
                    mDirty = true;
                    refreshOverlays();
                    GoUtils.DisplayToast(this, getString(R.string.route_cleared));
                })
                .show();
    }

    /* ============================ 绘制 ============================ */

    private void refreshOverlays() {
        if (mBaiduMap == null) {
            return;
        }
        mBaiduMap.clear();

        if (mPoints.size() >= 2) {
            List<LatLng> latLngs = new ArrayList<>();
            for (RoutePoint p : mPoints) {
                double[] bd = MapUtils.wgs2bd09(p.longitude, p.latitude);
                latLngs.add(new LatLng(bd[1], bd[0]));
            }
            mBaiduMap.addOverlay(new PolylineOptions()
                    .points(latLngs)
                    .width(10)
                    .color(0xAA2196F3));
        }

        for (int i = 0; i < mPoints.size(); i++) {
            RoutePoint p = mPoints.get(i);
            double[] bd = MapUtils.wgs2bd09(p.longitude, p.latitude);
            Bundle extra = new Bundle();
            extra.putInt(KEY_INDEX, i);
            MarkerOptions options = new MarkerOptions()
                    .position(new LatLng(bd[1], bd[0]))
                    .icon(makeNumberIcon(i + 1, i == 0 || i == mPoints.size() - 1))
                    .draggable(true)
                    .extraInfo(extra);
            mBaiduMap.addOverlay(options);
        }

        updateInfo();
    }

    private void updateInfo() {
        if (mInfoText == null) {
            return;
        }
        double len = 0;
        for (int i = 1; i < mPoints.size(); i++) {
            len += GeoUtils.distance(mPoints.get(i - 1).latitude, mPoints.get(i - 1).longitude,
                    mPoints.get(i).latitude, mPoints.get(i).longitude);
        }
        mInfoText.setText(getString(R.string.route_point_count, mPoints.size(), GeoUtils.formatDistance(len)));
    }

    /** 生成带序号的标记图标 */
    private BitmapDescriptor makeNumberIcon(int number, boolean endpoint) {
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (36 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(endpoint ? 0xFFE53935 : 0xFF1E88E5);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2 * density, paint);

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2 * density, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextSize(size * 0.5f);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(String.valueOf(number), size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, paint);

        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    /* ============================ 保存 ============================ */

    private void doSave() {
        if (mPoints.size() < 2) {
            GoUtils.DisplayToast(this, getString(R.string.route_need_two_points));
            return;
        }
        final EditText editText = new EditText(this);
        editText.setHint(R.string.route_name_hint);
        editText.setText(TextUtils.isEmpty(mRouteName) ? getString(R.string.route_name_default) : mRouteName);
        editText.setSingleLine(true);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        editText.setPadding(padding, padding / 2, padding, padding / 2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.route_save)
                .setView(editText)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = editText.getText() == null ? "" : editText.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = getString(R.string.route_name_default);
                    }
                    saveRoute(name);
                })
                .show();
    }

    private void saveRoute(String name) {
        if (mRouteDB == null) {
            GoUtils.DisplayToast(this, getString(R.string.route_save_failed));
            return;
        }
        long id;
        if (mRouteId > 0) {
            boolean ok = RouteStore.update(mRouteDB, mRouteId, name, mPoints);
            id = ok ? mRouteId : -1;
        } else {
            id = RouteStore.insert(mRouteDB, name, mPoints);
        }
        if (id > 0) {
            mRouteId = id;
            mRouteName = name;
            mDirty = false;
            GoUtils.DisplayToast(this, getString(R.string.route_saved));
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_ID, id);
            data.putExtra(EXTRA_ROUTE_NAME, name);
            setResult(RESULT_OK, data);
            finish();
        } else {
            GoUtils.DisplayToast(this, getString(R.string.route_save_failed));
        }
    }

    @Override
    public void onBackPressed() {
        if (mDirty && mPoints.size() >= 2) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.route_save)
                    .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                    .setPositiveButton(android.R.string.ok, (d, w) -> doSave())
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mMapView != null) {
            mMapView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMapView != null) {
            mMapView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        if (mMapView != null) {
            mMapView.onDestroy();
        }
        super.onDestroy();
    }
}
