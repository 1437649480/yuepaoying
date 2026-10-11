package com.zcshou.gogogo;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.database.DataBaseRoute;
import com.zcshou.gogogo.route.RoutePoint;
import com.zcshou.gogogo.route.RouteStore;
import com.zcshou.gogogo.utils.GeoUtils;
import com.zcshou.gogogo.utils.GoUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线列表（收藏）。
 *
 * 支持搜索、新建、编辑、重命名、复制、导出 GPX、导入 GPX 与删除。
 * 点击条目即回到主界面开始回放。
 */
public class RouteListActivity extends BaseActivity {

    public static final String EXTRA_RESULT_ID = "EXTRA_RESULT_ID";
    public static final String EXTRA_RESULT_NAME = "EXTRA_RESULT_NAME";
    public static final String EXTRA_CENTER_LNG = "EXTRA_CENTER_LNG";
    public static final String EXTRA_CENTER_LAT = "EXTRA_CENTER_LAT";

    private static final int REQ_EDIT = 1001;
    private static final int REQ_IMPORT_GPX = 1002;

    private static final String KEY_NAME = "name";
    private static final String KEY_INFO = "info";

    private ListView mListView;
    private TextView mEmptyView;
    private EditText mSearchEdit;
    private SimpleAdapter mAdapter;
    private final List<Map<String, Object>> mData = new ArrayList<>();
    private final List<RouteStore.RouteItem> mShown = new ArrayList<>();
    private List<RouteStore.RouteItem> mAllRoutes = new ArrayList<>();

    private SQLiteDatabase mRouteDB;
    private double mCenterLng = 116.397428;
    private double mCenterLat = 39.90923;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_list);

        Toolbar toolbar = findViewById(R.id.route_list_toolbar);
        setSupportActionBar(toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        mCenterLng = getIntent().getDoubleExtra(EXTRA_CENTER_LNG, mCenterLng);
        mCenterLat = getIntent().getDoubleExtra(EXTRA_CENTER_LAT, mCenterLat);

        try {
            mRouteDB = new DataBaseRoute(getApplicationContext()).getWritableDatabase();
        } catch (Exception e) {
            XLog.e("ERROR: route db init error");
        }

        mListView = findViewById(R.id.route_list_view);
        mEmptyView = findViewById(R.id.route_empty_view);
        mSearchEdit = findViewById(R.id.route_search_edit);

        mAdapter = new SimpleAdapter(this, mData, R.layout.route_item,
                new String[]{KEY_NAME, KEY_INFO},
                new int[]{R.id.route_item_name, R.id.route_item_info});
        mListView.setAdapter(mAdapter);
        mListView.setEmptyView(mEmptyView);

        mListView.setOnItemClickListener((AdapterView<?> parent, android.view.View view, int position, long id) -> {
            if (position < mShown.size()) {
                returnRoute(mShown.get(position));
            }
        });

        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < mShown.size()) {
                showItemMenu(mShown.get(position));
            }
            return true;
        });

        mSearchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString().trim());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.route_fab_add).setOnClickListener(v -> openEditor(RouteEditorActivity.NEW_ROUTE, ""));

        refreshList();
    }

    private void refreshList() {
        mAllRoutes = RouteStore.loadAll(mRouteDB);
        applyFilter(mSearchEdit.getText() == null ? "" : mSearchEdit.getText().toString().trim());
    }

    private void applyFilter(String keyword) {
        mShown.clear();
        for (RouteStore.RouteItem item : mAllRoutes) {
            if (keyword.isEmpty() || (item.name != null && item.name.contains(keyword))) {
                mShown.add(item);
            }
        }
        mData.clear();
        for (RouteStore.RouteItem item : mShown) {
            Map<String, Object> map = new HashMap<>();
            map.put(KEY_NAME, item.name);
            map.put(KEY_INFO, item.points.size() + " 个点 · "
                    + GeoUtils.formatDistance(item.lengthMeters())
                    + " · " + GoUtils.timeStamp2Date(String.valueOf(item.updateTime / 1000)));
            mData.add(map);
        }
        mAdapter.notifyDataSetChanged();
    }

    private void returnRoute(RouteStore.RouteItem item) {
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_ID, item.id);
        data.putExtra(EXTRA_RESULT_NAME, item.name);
        setResult(RESULT_OK, data);
        finish();
    }

    /* ============================ 条目操作 ============================ */

    private void showItemMenu(final RouteStore.RouteItem item) {
        String[] items = new String[]{
                getString(R.string.route_action_play),
                getString(R.string.route_action_edit),
                getString(R.string.route_action_rename),
                getString(R.string.route_action_duplicate),
                getString(R.string.route_action_export),
                getString(R.string.route_action_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            returnRoute(item);
                            break;
                        case 1:
                            openEditor(item.id, item.name);
                            break;
                        case 2:
                            showRenameDialog(item);
                            break;
                        case 3:
                            duplicateRoute(item);
                            break;
                        case 4:
                            exportGpx(item);
                            break;
                        case 5:
                            confirmDelete(item);
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openEditor(long id, String name) {
        Intent intent = new Intent(this, RouteEditorActivity.class);
        intent.putExtra(RouteEditorActivity.EXTRA_ROUTE_ID, id);
        intent.putExtra(RouteEditorActivity.EXTRA_ROUTE_NAME, name);
        intent.putExtra(RouteEditorActivity.EXTRA_CENTER_LNG, mCenterLng);
        intent.putExtra(RouteEditorActivity.EXTRA_CENTER_LAT, mCenterLat);
        startActivityForResult(intent, REQ_EDIT);
    }

    private void showRenameDialog(final RouteStore.RouteItem item) {
        final EditText editText = new EditText(this);
        editText.setText(item.name);
        editText.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.route_action_rename)
                .setView(editText)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = editText.getText() == null ? "" : editText.getText().toString().trim();
                    if (!TextUtils.isEmpty(name) && RouteStore.rename(mRouteDB, item.id, name)) {
                        GoUtils.DisplayToast(this, getString(R.string.route_renamed));
                        refreshList();
                    }
                })
                .show();
    }

    private void duplicateRoute(RouteStore.RouteItem item) {
        String name = item.name + " - 副本";
        long id = RouteStore.insert(mRouteDB, name, item.points);
        if (id > 0) {
            GoUtils.DisplayToast(this, getString(R.string.route_duplicated, name));
            refreshList();
        }
    }

    private void confirmDelete(final RouteStore.RouteItem item) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.route_delete_confirm, item.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (RouteStore.delete(mRouteDB, item.id)) {
                        GoUtils.DisplayToast(this, getString(R.string.route_deleted));
                        refreshList();
                    }
                })
                .show();
    }

    /* ============================ GPX ============================ */

    private void exportGpx(RouteStore.RouteItem item) {
        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "GoGoGo");
            if (!dir.exists() && !dir.mkdirs()) {
                GoUtils.DisplayToast(this, getString(R.string.route_export_failed));
                return;
            }
            String safeName = item.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File file = new File(dir, safeName + ".gpx");
            String gpx = RouteStore.toGpx(item.name, item.points);
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(gpx.getBytes("UTF-8"));
            }
            GoUtils.DisplayToast(this, getString(R.string.route_exported, file.getAbsolutePath()));
        } catch (Exception e) {
            XLog.e("ERROR: export gpx");
            GoUtils.DisplayToast(this, getString(R.string.route_export_failed));
        }
    }

    private void importGpx() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT_GPX);
        } catch (Exception e) {
            GoUtils.DisplayToast(this, getString(R.string.route_import_failed));
        }
    }

    private void handleImportResult(Uri uri) {
        if (uri == null) {
            return;
        }
        List<RoutePoint> points = new ArrayList<>();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            points = RouteStore.fromGpx(in);
        } catch (Exception e) {
            XLog.e("ERROR: import gpx");
        }
        if (points.size() < 2) {
            GoUtils.DisplayToast(this, getString(R.string.route_import_failed));
            return;
        }
        String name = guessNameFromUri(uri);
        long id = RouteStore.insert(mRouteDB, name, points);
        if (id > 0) {
            GoUtils.DisplayToast(this, getString(R.string.route_imported, points.size()));
            refreshList();
        }
    }

    private String guessNameFromUri(Uri uri) {
        String name = getString(R.string.route_name_default);
        try {
            String path = uri.getLastPathSegment();
            if (!TextUtils.isEmpty(path)) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0) {
                    path = path.substring(slash + 1);
                }
                int dot = path.lastIndexOf('.');
                if (dot > 0) {
                    path = path.substring(0, dot);
                }
                if (!path.isEmpty()) {
                    name = path;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return name;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT) {
            refreshList();
            if (resultCode == RESULT_OK && data != null) {
                long id = data.getLongExtra(RouteEditorActivity.EXTRA_RESULT_ID, -1);
                if (id > 0) {
                    Intent result = new Intent();
                    result.putExtra(EXTRA_RESULT_ID, id);
                    result.putExtra(EXTRA_RESULT_NAME, data.getStringExtra(RouteEditorActivity.EXTRA_ROUTE_NAME));
                    setResult(RESULT_OK, result);
                }
            }
        } else if (requestCode == REQ_IMPORT_GPX && resultCode == RESULT_OK) {
            if (data != null) {
                handleImportResult(data.getData());
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_route_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_import_gpx) {
            importGpx();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 供主界面菜单调用：导入 GPX */
    public void startImportGpx() {
        importGpx();
    }
}
