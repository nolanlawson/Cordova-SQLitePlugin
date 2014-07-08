/*
 * Copyright (c) 2012-2013, Chris Brody
 * Copyright (c) 2005-2010, Nitobi Software Inc.
 * Copyright (c) 2010, IBM Corporation
 */

package org.pgsqlite;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.lang.IllegalArgumentException;
import java.lang.Number;
import java.util.HashMap;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SQLitePlugin extends CordovaPlugin {
    /**
     * Multiple database map (static).
     */
    static HashMap<String, SQLiteDatabase> dbmap = new HashMap<String, SQLiteDatabase>();

    static HashMap<String, LooperThread> threadmap = new HashMap<String, LooperThread>();

    /**
     * Get a SQLiteDatabase reference from the db map (public static accessor).
     *
     * @param dbname The name of the database.
     */
    public static SQLiteDatabase getSQLiteDatabase(String dbname) {
        return dbmap.get(dbname);
    }

    /**
     * NOTE: Using default constructor, explicit constructor no longer required.
     */

    /**
     * Executes the request and returns PluginResult.
     *
     * @param actionAsString The action to execute.
     * @param args   JSONArry of arguments for the plugin.
     * @param cbc    Callback context from Cordova API
     * @return       Whether the action was valid.
     */
    @Override
    public boolean execute(String actionAsString, final JSONArray args, final CallbackContext cbc) {

        Action action;
        try {
            action = Action.valueOf(actionAsString);
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }

        try {
            return executeAndPossiblyThrow(action, args, cbc);
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }
    }

    private boolean executeAndPossiblyThrow(Action action, JSONArray args, CallbackContext cbc)
            throws JSONException {

        boolean status = true;
        JSONObject o;
        String dbname;

        switch (action) {
            case open:
                o = args.getJSONObject(0);
                dbname = o.getString("name");

                this.openDatabase(dbname, null);
                break;
            case close:
                o = args.getJSONObject(0);
                dbname = o.getString("path");

                this.closeDatabase(dbname);
                break;
            case delete:
                /* Stop & give up if API < 16: */
                if (android.os.Build.VERSION.SDK_INT < 16) {
                    return false;
                }

                o = args.getJSONObject(0);
                dbname = o.getString("path");

                status = this.deleteDatabase(dbname);
                break;
            case executePragmaStatement:
                dbname = args.getString(0);
                String query = args.getString(1);

                JSONArray jparams = (args.length() < 3) ? null : args.getJSONArray(2);

                String[] params = null;

                if (jparams != null) {
                    params = new String[jparams.length()];

                    for (int j = 0; j < jparams.length(); j++) {
                        if (jparams.isNull(j)) {
                            params[j] = "";
                        } else {
                            params[j] = jparams.getString(j);
                        }
                    }
                }

                Cursor myCursor = dbmap.get(dbname).rawQuery(query, params);

                String result = this.getRowsResultFromQuery(myCursor).getJSONArray("rows").toString();

                this.sendJavascriptCB("window.SQLitePluginCallback.p1('" + id + "', " + result + ");");
                break;
            case executeSqlBatch:
            case executeBatchTransaction:
            case backgroundExecuteSqlBatch:
                String[] queries = null;
                String[] queryIDs = null;

                JSONArray jsonArr = null;
                int paramLen = 0;
                JSONArray[] jsonparams = null;

                JSONObject allargs = args.getJSONObject(0);
                JSONObject dbargs = allargs.getJSONObject("dbargs");
                dbname = dbargs.getString("dbname");
                JSONArray txargs = allargs.getJSONArray("executes");

                if (txargs.isNull(0)) {
                    queries = new String[0];
                } else {
                    int len = txargs.length();
                    queries = new String[len];
                    queryIDs = new String[len];
                    jsonparams = new JSONArray[len];

                    for (int i = 0; i < len; i++) {
                        JSONObject a = txargs.getJSONObject(i);
                        queries[i] = a.getString("sql");
                        queryIDs[i] = a.getString("qid");
                        jsonArr = a.getJSONArray("params");
                        paramLen = jsonArr.length();
                        jsonparams[i] = jsonArr;
                    }
                }

                if (action == Action.backgroundExecuteSqlBatch) {
                    this.executeSqlBatchInBackground(dbname, queries, jsonparams, queryIDs, cbc);
                } else {

                    SQLiteDatabase mydb;
                    synchronized(this) {
                        mydb = dbmap.get(dbname);
                    }

                    this.executeSqlBatch(dbname, queries, jsonparams, queryIDs, mydb, cbc);
                }
                break;
        }

        return status;
    }

    /**
     * Clean up and close all open databases.
     */
    @Override
    public void onDestroy() {
        synchronized (this) {
            while (!dbmap.isEmpty()) {
                String dbname = dbmap.keySet().iterator().next();
                this.closeDatabase(dbname);
                dbmap.remove(dbname);
                threadmap.remove(dbname);
            }
        }
    }

    // --------------------------------------------------------------------------
    // LOCAL METHODS
    // --------------------------------------------------------------------------

    /**
     * Open a database.
     *
     * @param dbname   The name of the database-NOT including its extension.
     * @param password The database password or null.
     */
    private void openDatabase(String dbname, String password) {

        synchronized (this) {
            if (dbmap.get(dbname) != null) {
                this.closeDatabase(dbname);
            }

            File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

            if (!dbfile.exists()) {
                dbfile.getParentFile().mkdirs();
            }

            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
            Log.d("TAG", "SQLiteDatabase.openOrCreateDatabase(), dbName is: " + dbname);
            SQLiteDatabase mydb = SQLiteDatabase.openOrCreateDatabase(dbfile, null);

            dbmap.put(dbname, mydb);
            LooperThread thread = new LooperThread();
            thread.start();
            threadmap.put(dbname, thread);
        }
    }

    /**
     * Close a database.
     *
     * @param dbname The name of the database-NOT including its extension.
     */
    private void closeDatabase(String dbname) {
        synchronized (this) {
            SQLiteDatabase mydb = dbmap.get(dbname);

            if (mydb != null) {
                Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
                Log.d("TAG", "mydb.close(), dbname is " + dbname);
                mydb.close();
                dbmap.remove(dbname);
                threadmap.remove(dbname);
            }
        }
    }

    /**
     * Delete a database.
     *
     * @param dbname The name of the database-NOT including its extension.
     * @return true if successful or false if an exception was encountered
     */
    private boolean deleteDatabase(String dbname) {
        synchronized (this) {
            boolean status = false; // assume the worst case:

            if (dbmap.get(dbname) != null) {
                closeDatabase(dbname);
            }

            File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

            Log.v("info", "delete sqlite db: " + dbfile.getAbsolutePath());

            // Use try & catch just in case android.os.Build.VERSION.SDK_INT >= 16 was lying:
            try {
                status = SQLiteDatabase.deleteDatabase(dbfile);
            } catch (Exception ex) {
                // log & give up:
                Log.v("executeSqlBatch", "deleteDatabase(): Error=" + ex.getMessage());
                ex.printStackTrace();
            }

            return status;
        }
    }

    /**
     * Executes a batch request IN BACKGROUND THREAD and sends the results via sendJavascriptCB().
     *
     * @param dbName     The name of the database.
     * @param queryarr   Array of query strings
     * @param jsonparams Array of JSON query parameters
     * @param queryIDs   Array of query ids
     * @param cbc        Callback context from Cordova API
     */
    private void executeSqlBatchInBackground(final String dbName,
                                             final String[] queryarr, final JSONArray[] jsonparams,
                                             final String[] queryIDs, final CallbackContext cbc) {
        LooperThread thread;

        synchronized (this) {
            thread = threadmap.get(dbName);
        }

        thread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase mydb;
                synchronized (SQLitePlugin.this) {
                    mydb = dbmap.get(dbName);
                }
                executeSqlBatch(dbName, queryarr, jsonparams, queryIDs, mydb, cbc);
            }
        });
    }

    /**
     * Executes a batch request and sends the results via sendJavascriptCB().
     *
     * @param dbname     The name of the database.
     * @param queryarr   Array of query strings
     * @param jsonparams Array of JSON query parameters
     * @param queryIDs   Array of query ids
     * @param cbc        Callback context from Cordova API
     */
    private void executeSqlBatch(String dbname, String[] queryarr, JSONArray[] jsonparams,
                                 String[] queryIDs, SQLiteDatabase mydb, CallbackContext cbc) {

        String query = "";
        String query_id = "";
        int len = queryarr.length;

        Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
        Log.d("TAG", "queries: " + java.util.Arrays.toString(queryarr));

        JSONArray batchResults = new JSONArray();

        for (int i = 0; i < len; i++) {
            query_id = queryIDs[i];

            JSONObject queryResult = null;
            String errorMessage = "unknown";

            try {
                boolean needRawQuery = true;

                query = queryarr[i];

                // UPDATE or DELETE:
                // NOTE: this code should be safe to RUN with old Android SDK.
                // To BUILD with old Android SDK remove lines from HERE: {{
                if (android.os.Build.VERSION.SDK_INT >= 11 &&
                        (query.toLowerCase().startsWith("update") ||
                                query.toLowerCase().startsWith("delete"))) {
                    SQLiteStatement myStatement = mydb.compileStatement(query);

                    if (jsonparams != null) {
                        for (int j = 0; j < jsonparams[i].length(); j++) {
                            if (jsonparams[i].get(j) instanceof Float || jsonparams[i].get(j) instanceof Double) {
                                myStatement.bindDouble(j + 1, jsonparams[i].getDouble(j));
                            } else if (jsonparams[i].get(j) instanceof Number) {
                                myStatement.bindLong(j + 1, jsonparams[i].getLong(j));
                            } else if (jsonparams[i].isNull(j)) {
                                myStatement.bindNull(j + 1);
                            } else {
                                myStatement.bindString(j + 1, jsonparams[i].getString(j));
                            }
                        }
                    }

                    int rowsAffected = -1; // (assuming invalid)

                    // Use try & catch just in case android.os.Build.VERSION.SDK_INT >= 11 is lying:
                    try {
                        Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
                        Log.d("TAG", "executeUpdateOrDelete();, dbname is: " + dbname);
                        rowsAffected = myStatement.executeUpdateDelete();
                        // Indicate valid results:
                        needRawQuery = false;
                    } catch (SQLiteException ex) {
                        // Indicate problem & stop this query:
                        ex.printStackTrace();
                        errorMessage = ex.getMessage();
                        Log.v("executeSqlBatch", "SQLiteStatement.executeUpdateDelete(): Error=" + errorMessage);
                        needRawQuery = false;
                    } catch (Exception ex) {
                        // Assuming SDK_INT was lying & method not found:
                        // do nothing here & try again with raw query.
                    }

                    if (rowsAffected != -1) {
                        queryResult = new JSONObject();
                        queryResult.put("rowsAffected", rowsAffected);
                    }
                } // to HERE. }}

                Log.d("q: ", query);

                // INSERT:
                if (query.toLowerCase().startsWith("insert") && jsonparams != null) {
                    needRawQuery = false;

                    SQLiteStatement myStatement = mydb.compileStatement(query);

                    for (int j = 0; j < jsonparams[i].length(); j++) {
                        if (jsonparams[i].get(j) instanceof Float || jsonparams[i].get(j) instanceof Double) {
                            myStatement.bindDouble(j + 1, jsonparams[i].getDouble(j));
                        } else if (jsonparams[i].get(j) instanceof Number) {
                            myStatement.bindLong(j + 1, jsonparams[i].getLong(j));
                        } else if (jsonparams[i].isNull(j)) {
                            myStatement.bindNull(j + 1);
                        } else {
                            myStatement.bindString(j + 1, jsonparams[i].getString(j));
                        }
                    }

                    long insertId = -1; // (invalid)
                    try {
                        Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
                        Log.d("TAG", "executeInsert();, dbname is: " + dbname);
                        insertId = myStatement.executeInsert();
                    } catch (SQLiteException ex) {
                        ex.printStackTrace();
                        errorMessage = ex.getMessage();
                        Log.v("executeSqlBatch", "SQLiteDatabase.executeInsert(): Error=" + errorMessage);
                    }

                    if (insertId != -1) {
                        queryResult = new JSONObject();
                        queryResult.put("insertId", insertId);
                        queryResult.put("rowsAffected", 1);
                    }
                }

                if (query.toLowerCase().startsWith("begin")) {
                    needRawQuery = false;
                    try {
                        Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
                        Log.d("TAG", "mydb.beginTransaction();, dbname is: " + dbname);
                        mydb.beginTransaction();

                        queryResult = new JSONObject();
                        queryResult.put("rowsAffected", 0);
                    } catch (SQLiteException ex) {
                        ex.printStackTrace();
                        errorMessage = ex.getMessage();
                        Log.v("executeSqlBatch", "SQLiteDatabase.beginTransaction(): Error=" + errorMessage);
                    }
                }

                if (query.toLowerCase().startsWith("commit")) {
                    needRawQuery = false;
                    try {
                        Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
                        Log.d("TAG", "mydb.setTransactionSuccessful();, dbname is: " + dbname);
                        mydb.setTransactionSuccessful();
                        Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
                        Log.d("TAG", "mydb.endTransaction();, dbname is: " + dbname);
                        mydb.endTransaction();

                        queryResult = new JSONObject();
                        queryResult.put("rowsAffected", 0);
                    } catch (SQLiteException ex) {
                        ex.printStackTrace();
                        errorMessage = ex.getMessage();
                        Log.v("executeSqlBatch", "SQLiteDatabase.setTransactionSuccessful/endTransaction(): Error=" + errorMessage);
                    }
                }

                if (query.toLowerCase().startsWith("rollback")) {
                    needRawQuery = false;
                    try {
                        Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
                        Log.d("TAG", "mydb.endTransaction();, dbname is: " + dbname);
                        mydb.endTransaction();

                        queryResult = new JSONObject();
                        queryResult.put("rowsAffected", 0);
                    } catch (SQLiteException ex) {
                        ex.printStackTrace();
                        errorMessage = ex.getMessage();
                        Log.v("executeSqlBatch", "SQLiteDatabase.endTransaction(): Error=" + errorMessage);
                    }
                }

                // raw query for other statements:
                if (needRawQuery) {
                    String[] params = null;

                    if (jsonparams != null) {
                        params = new String[jsonparams[i].length()];

                        for (int j = 0; j < jsonparams[i].length(); j++) {
                            if (jsonparams[i].isNull(j))
                                params[j] = "";
                            else
                                params[j] = jsonparams[i].getString(j);
                        }
                    }
                    Log.d("Thread", Long.toString(Thread.currentThread().getId(), 16));
                    Log.d("TAG", "mydb.rawQuery();, dbname is: " + dbname);
                    Cursor myCursor = mydb.rawQuery(query, params);

                    if (query_id.length() > 0) {
                        queryResult = this.getRowsResultFromQuery(myCursor);
                    }

                    myCursor.close();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                errorMessage = ex.getMessage();
                Log.v("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + errorMessage);
            }

            try {
                if (queryResult != null) {
                    JSONObject r = new JSONObject();
                    r.put("qid", query_id);

                    r.put("type", "success");
                    r.put("result", queryResult);

                    batchResults.put(r);
                } else {
                    JSONObject r = new JSONObject();
                    r.put("qid", query_id);
                    r.put("type", "error");

                    JSONObject er = new JSONObject();
                    er.put("message", errorMessage);
                    r.put("result", er);

                    batchResults.put(r);
                }
            } catch (JSONException ex) {
                ex.printStackTrace();
                Log.v("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + ex.getMessage());
                // TODO what to do?
            }
        }

        cbc.success(batchResults);
    }

    /**
     * Get rows results from query cursor.
     *
     * @param cur Cursor into query results
     * @return results in string form
     */
    private JSONObject getRowsResultFromQuery(Cursor cur) {
        JSONObject rowsResult = new JSONObject();

        // If query result has rows
        if (cur.moveToFirst()) {
            JSONArray rowsArrayResult = new JSONArray();
            String key = "";
            int colCount = cur.getColumnCount();

            // Build up JSON result object for each row
            do {
                JSONObject row = new JSONObject();
                try {
                    for (int i = 0; i < colCount; ++i) {
                        key = cur.getColumnName(i);

                        // NOTE: this code should be safe to RUN with old Android SDK.
                        // To BUILD with old Android SDK remove lines from HERE: {{
                        if (android.os.Build.VERSION.SDK_INT >= 11) {
                            int curType = 3; /* Cursor.FIELD_TYPE_STRING */

                            // Use try & catch just in case android.os.Build.VERSION.SDK_INT >= 11 is lying:
                            try {
                                curType = cur.getType(i);

                                switch (curType) {
                                    case Cursor.FIELD_TYPE_NULL:
                                        row.put(key, JSONObject.NULL);
                                        break;
                                    case Cursor.FIELD_TYPE_INTEGER:
                                        row.put(key, cur.getLong(i));
                                        break;
                                    case Cursor.FIELD_TYPE_FLOAT:
                                        row.put(key, cur.getDouble(i));
                                        break;
                                    case Cursor.FIELD_TYPE_BLOB:
                                        row.put(key, new String(Base64.encode(cur.getBlob(i), Base64.DEFAULT)));
                                        break;
                                    case Cursor.FIELD_TYPE_STRING:
                                    default: /* (not expected) */
                                        row.put(key, cur.getString(i));
                                        break;
                                }

                            } catch (Exception ex) {
                                // simply treat like a string
                                row.put(key, cur.getString(i));
                            }
                        } else // to HERE. }}
                        {
                            row.put(key, cur.getString(i));
                        }
                    }

                    rowsArrayResult.put(row);

                } catch (JSONException e) {
                    e.printStackTrace();
                }

            } while (cur.moveToNext());

            try {
                rowsResult.put("rows", rowsArrayResult);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        return rowsResult;
    }

    /**
     * Send Javascript callback.
     *
     * @param cb Javascript callback command to send
     */
    private void sendJavascriptCB(String cb) {
        this.webView.sendJavascript(cb);
    }

    /**
     * Send Javascript callback on GUI thread.
     *
     * @param cb Javascript callback command to send
     */
    private void sendJavascriptToGuiThread(final String cb) {
        final SQLitePlugin myself = this;

        this.cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                myself.webView.sendJavascript(cb);
            }
        });
    }

    private static enum Action {
        open,
        close,
        delete,
        executePragmaStatement,
        executeSqlBatch,
        executeBatchTransaction,
        backgroundExecuteSqlBatch,
    }

    private static class LooperThread extends Thread {
        public Handler mHandler;

        public void run() {
            Looper.prepare();

            mHandler = new Handler();

            Looper.loop();
        }

        public Handler getHandler() {
            return mHandler;
        }
    }
}
