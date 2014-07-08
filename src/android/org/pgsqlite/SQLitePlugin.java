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

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.lang.IllegalArgumentException;
import java.lang.Number;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;

import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SQLitePlugin extends CordovaPlugin {

    // turn on if you want noisy log output
    private static final boolean DEBUG = false;

    /**
     * Multiple database map (static).
     */
    private Map<String, DatabaseContext> contextMap;

    private LooperThread backgroundThread;

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
            error("unexpected error", e);
            return false;
        }

        try {
            executeAndPossiblyThrow(action, args, cbc);
            return true;
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            error("unexpected error", e);
            return false;
        }
    }

    private void executeAndPossiblyThrow(Action action, JSONArray args, CallbackContext cbc)
            throws JSONException {

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
                    return;
                }

                o = args.getJSONObject(0);
                dbname = o.getString("path");

                this.deleteDatabase(dbname);

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
                Cursor myCursor = contextMap.get(dbname).getDatabase().rawQuery(query, params);

                String result = this.getRowsResultFromQuery(myCursor).getJSONArray("rows").toString();

                this.sendJavascriptCB("window.SQLitePluginCallback.p1('" + id + "', " + result + ");");
                break;
            case executeSqlBatch:
            case executeBatchTransaction:
            case backgroundExecuteSqlBatch:
                String[] queries;
                int[] queryIDs = null;
                int transactionId = -1;

                JSONArray jsonArr;
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
                    queryIDs = new int[len];
                    jsonparams = new JSONArray[len];

                    for (int i = 0; i < len; i++) {
                        JSONObject a = txargs.getJSONObject(i);
                        queries[i] = a.getString("sql");
                        queryIDs[i] = a.getInt("qid");
                        transactionId = a.getInt("txid");
                        jsonArr = a.getJSONArray("params");
                        jsonparams[i] = jsonArr;
                    }
                }

                if (action == Action.backgroundExecuteSqlBatch) {
                    this.executeSqlBatchInBackground(dbname, transactionId, queries, jsonparams, queryIDs, cbc);
                } else {
                    DatabaseContext dbContext = contextMap.get(dbname);

                    if (dbContext != null) {
                        this.executeSqlBatch(dbContext, queries, jsonparams, queryIDs, cbc);
                    }
            }
                break;
        }
    }


    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        contextMap = new HashMap<String, DatabaseContext>();
        backgroundThread = new LooperThread();
        backgroundThread.start();
    }

    /**
     * Clean up and close all open databases.
     */
    @Override
    public void onDestroy() {
        while (!contextMap.isEmpty()) {
            String dbname = contextMap.keySet().iterator().next();
            SQLitePlugin.this.closeDatabase(dbname);
            contextMap.remove(dbname);
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
    private void openDatabase(final String dbname, final String password) {

        backgroundThread.post(new Runnable() {
            @Override
            public void run() {
                if (contextMap.get(dbname) != null) {
                    SQLitePlugin.this.closeDatabase(dbname);
                }

                File dbfile = SQLitePlugin.this.cordova.getActivity().getDatabasePath(dbname);

                if (!dbfile.exists()) {
                    dbfile.getParentFile().mkdirs();
                }

                debug("Open sqlite db: " + dbfile.getAbsolutePath());

                debug("SQLiteDatabase.openOrCreateDatabase(), dbName is: " + dbname);
                SQLiteDatabase mydb = SQLiteDatabase.openOrCreateDatabase(dbfile, null);

                DatabaseContext dbContext = new DatabaseContext();

                dbContext.setDatabase(mydb);
                dbContext.setQueue(new PriorityQueue<ExecuteSqlCall>());
                dbContext.setDbName(dbname);

                contextMap.put(dbname, dbContext);
            }
        });
    }

    /**
     * Close a database.
     *
     * @param dbname The name of the database-NOT including its extension.
     */
    private void closeDatabase(final String dbname) {
        backgroundThread.post(new Runnable() {
                @Override
                public void run() {
                DatabaseContext dbContext = contextMap.get(dbname);

                if (dbContext != null) {
                    debug("mydb.close(), dbname is " + dbname);
                    dbContext.getDatabase().close();
                    contextMap.remove(dbname);
                }
            }
        });
    }

    /**
     * Delete a database.
     *
     * @param dbname The name of the database-NOT including its extension.
     * @return true if successful or false if an exception was encountered
     */
    private void deleteDatabase(final String dbname) {
        backgroundThread.post(new Runnable() {
            @Override
            public void run() {
                if (contextMap.get(dbname) != null) {
                    closeDatabase(dbname);
                }

                File dbfile = SQLitePlugin.this.cordova.getActivity().getDatabasePath(dbname);

                debug("delete sqlite db: " + dbfile.getAbsolutePath());

                // Use try & catch just in case android.os.Build.VERSION.SDK_INT >= 16 was lying:
                try {
                    SQLiteDatabase.deleteDatabase(dbfile);
                } catch (Exception ex) {
                    // log & give up:
                    error("executeSqlBatch: deleteDatabase()", ex);
                }

            }
        });
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
    private void executeSqlBatchInBackground(String dbName, int transactionId,
                                             String[] queryarr, JSONArray[] jsonparams,
                                             int[] queryIDs, CallbackContext cbc) {
        final DatabaseContext dbContext = contextMap.get(dbName);

        if (dbContext == null) {
            return;
        }

        debug("adding: " + java.util.Arrays.toString(queryarr));
        dbContext.getQueue().add(
                new ExecuteSqlCall(dbName, transactionId, queryarr, jsonparams, queryIDs, cbc));

        backgroundThread.post(new ProcessQueueRunnable(dbContext));
    }


    /**
     * Executes a batch request and sends the results via sendJavascriptCB().
     *
     * @param queryarr   Array of query strings
     * @param jsonparams Array of JSON query parameters
     * @param queryIDs   Array of query ids
     * @param cbc        Callback context from Cordova API
     */
    private void executeSqlBatch(DatabaseContext databaseContext, String[] queryarr, JSONArray[] jsonparams,
                                 int[] queryIDs, CallbackContext cbc) {

        SQLiteDatabase mydb = databaseContext.getDatabase();
        String dbname = databaseContext.getDbName();
        String query = "";
        int len = queryarr.length;

        debug("queries: " + java.util.Arrays.toString(queryarr));

        JSONArray batchResults = new JSONArray();

        for (int i = 0; i < len; i++) {
            int query_id = queryIDs[i];

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
                        debug("executeUpdateOrDelete();, dbname is: " + dbname);
                        rowsAffected = myStatement.executeUpdateDelete();
                        // Indicate valid results:
                        needRawQuery = false;
                    } catch (SQLiteException ex) {
                        // Indicate problem & stop this query:
                        error("executeSqlBatch: SQLiteStatement.executeUpdateDelete()", ex);
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

                debug("query: " + query);

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
                        debug("executeInsert();, dbname is: " + dbname);
                        insertId = myStatement.executeInsert();
                    } catch (SQLiteException ex) {
                        verbose("executeSqlBatch: SQLiteDatabase.executeInsert()", ex);
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
                        debug("begin, dbname is: " + dbname);
                        mydb.beginTransaction();

                        queryResult = new JSONObject();
                        queryResult.put("rowsAffected", 0);
                    } catch (SQLiteException ex) {
                        verbose("executeSqlBatch: SQLiteDatabase.beginTransaction()", ex);
                    }
                }

                if (query.toLowerCase().startsWith("commit")) {
                    needRawQuery = false;
                    try {
                        debug("commit, dbname is: " + dbname);
                        mydb.setTransactionSuccessful();
                        mydb.endTransaction();
                        databaseContext.setCurrentTransaction(-1); // reset

                        queryResult = new JSONObject();
                        queryResult.put("rowsAffected", 0);
                    } catch (SQLiteException ex) {
                        verbose("executeSqlBatch: SQLiteDatabase.setTransactionSuccessful/endTransaction()", ex);
                    }
                }

                if (query.toLowerCase().startsWith("rollback")) {
                    needRawQuery = false;
                    try {
                        debug("rollback, dbname is: " + dbname);
                        mydb.endTransaction();
                        databaseContext.setCurrentTransaction(-1); // reset

                        queryResult = new JSONObject();
                        queryResult.put("rowsAffected", 0);
                    } catch (SQLiteException ex) {
                        verbose("executeSqlBatch: SQLiteDatabase.endTransaction():", ex);
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
                    debug("  raqQuery, dbname is: " + dbname);
                    Cursor myCursor = mydb.rawQuery(query, params);

                    queryResult = this.getRowsResultFromQuery(myCursor);

                    debug("  results: " + queryResult.toString());

                    myCursor.close();
                }
            } catch (Exception ex) {
                verbose("executeSqlBatch: SQLitePlugin.executeSql[Batch]()", ex);
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
                verbose("executeSqlBatch: SQLitePlugin.executeSql[Batch]()", ex);
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
                    error("unexpected JSONException", e);
                }

            } while (cur.moveToNext());

            try {
                rowsResult.put("rows", rowsArrayResult);
            } catch (JSONException e) {
                error("unexpected JSONException", e);
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

    private static void debug(String message) {
        if (DEBUG) {
            Log.d(SQLitePlugin.class.getSimpleName(), message);
        }
    }

    private static void error(String message, Exception e) {
        Log.e(SQLitePlugin.class.getSimpleName(), message, e);
    }

    private static void verbose(String message, Exception e) {
        Log.v(SQLitePlugin.class.getSimpleName(), message, e);
    }

    /**
     * Runnable responsible for handling the next executeSqlBatch in the queue (when background
     * processing is enabled).
     *
     * Basically the idea is that this runnable takes a single element off the front of the queue,
     * which is ordered by transaction ID, then query ID (in ascending order). If there's nothing
     * left in the queue, it stops.  If there's anything in the queue, it processes the first one
     * and then posts to its own handler to start the process again.
     *
     * Since it operates on a single queue, operations are synchronized.
     */
    private class ProcessQueueRunnable implements Runnable {

        private DatabaseContext databaseContext;

        public ProcessQueueRunnable(DatabaseContext databaseContext) {
            this.databaseContext = databaseContext;
        }

        @Override
        public void run() {
            PriorityQueue<ExecuteSqlCall> queue = databaseContext.getQueue();

            ExecuteSqlCall sqlCall = queue.peek();

            if (sqlCall == null) { // queue is empty
                return;
            }
            int currentTransactionId = databaseContext.getCurrentTransaction();
            if (currentTransactionId != -1 && currentTransactionId != sqlCall.getTransactionId()) {
                debug("another transaction is already in progress: " + currentTransactionId);
                // some other transaction is in progress, need to wait
                backgroundThread.post(this);
                return;
            }
            debug("excuting transaction: " + sqlCall.getTransactionId());
            databaseContext.setCurrentTransaction(sqlCall.getTransactionId());

            sqlCall = queue.poll();

            debug("executing: " + java.util.Arrays.toString(sqlCall.getQueries()));
            debug("txId: " + sqlCall.getTransactionId());
            debug("qIds: " + java.util.Arrays.toString(sqlCall.getQueryIDs()));
            executeSqlBatch(databaseContext, sqlCall.getQueries(),
                    sqlCall.getJsonParams(),sqlCall.getQueryIDs(), sqlCall.getCallbackContext());
            backgroundThread.post(this);
        }
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

    /**
     * Abstraction of an executeSqlBatch call given to us by SQLitePlugin.js
     *
    */
    private static class ExecuteSqlCall implements Comparable<ExecuteSqlCall> {

        private int transactionId;
        private int maxQueryId;
        private String[] queries;
        private JSONArray[] jsonParams;
        private int[] queryIDs;
        private String dbName;
        private CallbackContext callbackContext;

        public ExecuteSqlCall(String dbName,
                              int transactionId, String[] queries, JSONArray[] jsonParams,
                              int[] queryIDs, CallbackContext callbackContext) {

            this.dbName = dbName;
            this.transactionId = transactionId;
            this.queries = queries;
            this.jsonParams = jsonParams;
            this.queryIDs = queryIDs;
            this.callbackContext = callbackContext;

            this.maxQueryId = 0;

            for (int queryId : queryIDs) {
                if (queryId > this.maxQueryId) {
                    this.maxQueryId = queryId;
                }
            }
        }

        /**
         * Overriding this is required because we're going to stuff this in a PriorityQueue
         * eventually.  It's sorted by transactionId, then the max queryId, which gives the proper
         * execution order when sequentially performing transactions on a single database.
         *
         * @param other
         * @return
         */
        @Override
        public int compareTo(ExecuteSqlCall other) {
            int tIdCompare = Integer.compare(transactionId, other.transactionId);
            if (tIdCompare != 0) {
                return tIdCompare;
            }
            return Integer.compare(maxQueryId, other.maxQueryId);
        }

        public int getTransactionId() {
            return transactionId;
        }

        public String[] getQueries() {
            return queries;
        }

        public JSONArray[] getJsonParams() {
            return jsonParams;
        }

        public int[] getQueryIDs() {
            return queryIDs;
        }

        public String getDbName() {
            return dbName;
        }

        public CallbackContext getCallbackContext() {
            return callbackContext;
        }
    }

    /**
     * LooperThread, as discussed in https://developer.android.com/reference/android/os/Looper.html
     *
     * Basically this is a cheap way for us to get a single Thread that has a Handler and is
     * guaranteed to run on the background.
     *
     * In other words: ask yourself the question, "If Android has a single UI thread, how can I get
     * a _single_ background thread?" This is the answer.
     *
     *
     */
    private static class LooperThread extends Thread {

        private Handler handler;
        private BlockingQueue<Runnable> initQueue = new LinkedBlockingQueue<Runnable>();

        public void run() {
            Looper.prepare();

            handler = new Handler();

            Looper.loop();

            // weren't ready yet, but now we can execute them
            Runnable runnable;
            try {
                while ((runnable = initQueue.take()) != null) {
                    handler.post(runnable);
                }
            } catch (InterruptedException e) {
                // shouldn't happen
                error("unexpected", e);
            }

        }

        public void post(Runnable runnable) {
            if (handler != null) {
                handler.post(runnable);
            } else {
                try {
                    initQueue.put(runnable);
                } catch (InterruptedException e) {
                    // shouldn't happen
                    error("unexpected", e);
                }
            }
        }
    }

    /**
     * Wrapper object describing what we need to work with a given SQLite Database
     * in a threadsafe way.
     */
    private static class DatabaseContext {

        private SQLiteDatabase database;
        private PriorityQueue<ExecuteSqlCall> queue;
        private int currentTransaction = -1; // transaction Ids start counting at 0
        private String dbName;

        public String getDbName() {
            return dbName;
        }

        public void setDbName(String dbName) {
            this.dbName = dbName;
        }

        public SQLiteDatabase getDatabase() {
            return database;
        }

        public void setDatabase(SQLiteDatabase database) {
            this.database = database;
        }

        public PriorityQueue<ExecuteSqlCall> getQueue() {
            return queue;
        }

        public void setQueue(PriorityQueue<ExecuteSqlCall> queue) {
            this.queue = queue;
        }

        public int getCurrentTransaction() {
            return currentTransaction;
        }

        public void setCurrentTransaction(int currentTransaction) {
            this.currentTransaction = currentTransaction;
        }
    }
}
