package com.zcshou.gogogo.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 路线数据库。
 *
 * 与位置历史 / 搜索历史分开建库，避免相互影响（2.4.1 中位置历史与路线历史也使用独立任务队列）。
 */
public class DataBaseRoute extends SQLiteOpenHelper {

    private static final int DB_VERSION = 1;
    private static final String DB_NAME = "DB_ROUTE.db";

    public static final String TABLE_NAME = "TABLE_ROUTE";
    public static final String DB_COLUMN_ID = "DB_COLUMN_ID";
    public static final String DB_COLUMN_NAME = "DB_COLUMN_NAME";
    public static final String DB_COLUMN_POINTS = "DB_COLUMN_POINTS";
    public static final String DB_COLUMN_TIMESTAMP = "DB_COLUMN_TIMESTAMP";
    public static final String DB_COLUMN_UPDATE = "DB_COLUMN_UPDATE";

    public DataBaseRoute(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        String createTable = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                + DB_COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + DB_COLUMN_NAME + " TEXT NOT NULL, "
                + DB_COLUMN_POINTS + " TEXT NOT NULL, "
                + DB_COLUMN_TIMESTAMP + " TEXT NOT NULL, "
                + DB_COLUMN_UPDATE + " TEXT NOT NULL)";
        sqLiteDatabase.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        // 首个版本，暂无迁移逻辑；后续如需变更结构在此处理
    }
}
