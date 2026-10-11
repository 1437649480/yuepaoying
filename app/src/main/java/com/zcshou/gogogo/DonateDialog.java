package com.zcshou.gogogo;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;

import androidx.appcompat.app.AlertDialog;

import com.elvishew.xlog.XLog;

import java.util.Calendar;

/**
 * 启动时的打赏提示弹窗。
 * 勾选「一天内不再显示」后，24 小时内不再自动弹出。
 */
public class DonateDialog {

    private static final String PREF_NAME = "donate_dialog";
    private static final String KEY_LAST_SHOW = "last_show_time";
    private static final long INTERVAL_MS = 24 * 60 * 60 * 1000L;

    private DonateDialog() {
    }

    /**
     * 需要时弹出打赏提示。
     *
     * @param context 需要 Activity context（用于启动 AboutActivity）
     */
    public static void showIfNeeded(MainActivity context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            long last = sp.getLong(KEY_LAST_SHOW, 0L);
            long now = System.currentTimeMillis();

            // 首次启动，或距上次展示已超过 24 小时
            if (last != 0L && now - last < INTERVAL_MS) {
                return;
            }

            show(context, sp);
        } catch (Exception e) {
            XLog.e("ERROR: show donate dialog failed");
        }
    }

    private static void show(MainActivity context, SharedPreferences sp) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_donate, null, false);

        // 收款码按原比例显示，保证可扫描
        ImageView qr = view.findViewById(R.id.dialog_donate_qr);
        qr.setAdjustViewBounds(true);

        final CheckBox notToday = view.findViewById(R.id.dialog_donate_not_today);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(true)
                .setNegativeButton(R.string.dialog_donate_more, (d, w) -> {
                    context.startActivity(new android.content.Intent(context, AboutActivity.class));
                })
                .setPositiveButton(R.string.dialog_donate_close, null)
                .create();

        dialog.setOnDismissListener(d -> {
            // 只有勾选「一天内不再显示」才写入时间戳进行抑制；
            // 未勾选则记录 0，下次启动继续提示。
            long stamp = notToday.isChecked() ? System.currentTimeMillis() : 0L;
            sp.edit().putLong(KEY_LAST_SHOW, stamp).apply();
        });

        dialog.show();
    }

    /**
     * 提供给设置项手动重置使用。
     */
    public static void reset(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST_SHOW).apply();
    }
}