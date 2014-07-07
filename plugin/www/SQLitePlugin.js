(function () {
  'use strict';

  var DEBUG = true;
  var READ_ONLY_REGEX = /^\s*(?:drop|delete|insert|update|create)\s/i;

  function log(msg) {
    if (DEBUG) {
      console.log(msg);
    }
  }

  var nextTick = window.setImmediate || function (fun) { window.setTimeout(fun, 0); };
  var noop = function () {};

  // -----------------------------------
  // SQLitePlugin
  // -----------------------------------

  function SQLitePlugin(openargs, openSuccess, openError) {
    log("SQLitePlugin openargs: " + (JSON.stringify(openargs)));
    if (!(openargs && openargs['name'])) {
      throw new Error("Cannot create a SQLitePlugin instance without a db name");
    }
    this.openargs = openargs;
    this.dbname = openargs.name;
    this.openSuccess = openSuccess || function () {
      log("DB opened: " + openargs.name);
    };
    this.openError = openError || function (e) {
      log(e.message);
    };
    var isIos = /iP(?:hone|ad|od)/.test(navigator.userAgent);
    this.bg = 'bgType' in openargs ? openargs.bgType : isIos;
    this.open(this.openSuccess, this.openError);
  }

  SQLitePlugin.prototype.databaseFeatures = {
    isSQLitePluginDatabase: true
  };

  SQLitePlugin.prototype.openDBs = {};

  SQLitePlugin.prototype.txQueue = [];

  SQLitePlugin.prototype.addTransaction = function (t) {
    this.txQueue.push(t);
    this.startNextTransaction();
  };

  SQLitePlugin.prototype.transaction = function (fn, error, success) {
    this.addTransaction(new SQLitePluginTransaction(this, fn, error, success, true, false));
  };

  SQLitePlugin.prototype.readTransaction = function (fn, error, success) {
    this.addTransaction(new SQLitePluginTransaction(this, fn, error, success, true, true));
  };

  SQLitePlugin.prototype.startNextTransaction = function () {
    var self = this;
    nextTick(function () {
      if (self.txQueue.length > 0) {
        self.txQueue.shift().start();
      }
    });
  };

  SQLitePlugin.prototype.open = function (success, error) {
    if (!(this.dbname in this.openDBs)) {
      this.openDBs[this.dbname] = true;
      cordova.exec(success, error, "SQLitePlugin", "open", [this.openargs]);
    }
  };

  SQLitePlugin.prototype.close = function (success, error) {
    if (this.dbname in this.openDBs) {
      delete this.openDBs[this.dbname];
      cordova.exec(null, null, "SQLitePlugin", "close", [
        {
          path: this.dbname
        }
      ]);
    }
  };

  SQLitePlugin.prototype.executeSql = function (statement, params, success, error) {

    var mysuccess = function (txn, result) {
      if (success) {
        return success(result);
      }
    };
    var myerror = function (txn, err) {
      if (error) {
        return error(err);
      }
    };
    var myfn = function (tx) {
      tx.executeSql(statement, params, mysuccess, myerror);
    };
    this.addTransaction(new SQLitePluginTransaction(this, myfn, myerror, mysuccess, false));
  };

  // -----------------------------------
  // pcb
  // -----------------------------------

  var pcb = function () {
    return 1;
  };

  /*
  DEPRECATED AND WILL BE REMOVED:
  */


  SQLitePlugin.prototype.executePragmaStatement = function (statement, success, error) {
    log("SQLitePlugin::executePragmaStatement");
    pcb = success;
    cordova.exec(function () {
      return 1;
    }, error, "SQLitePlugin", "executePragmaStatement", [this.dbname, statement]);
  };

  /*
  Transaction batching object:
  */

  // -----------------------------------
  // SQLitePluginTransaction
  // -----------------------------------

  function SQLitePluginTransaction(db, fn, error, success, txlock, readOnly) {
    if (typeof fn !== "function") {
      /*
      This is consistent with the implementation in Chrome -- it
      throws if you pass anything other than a function. This also
      prevents us from stalling our txQueue if somebody passes a
      false value for fn.
      */

      throw new Error("transaction expected a function");
    }
    this.db = db;
    this.fn = fn || noop;
    this.error = error || noop;
    this.success = success || noop;
    this.txlock = txlock;
    this.readOnly = readOnly;
    this.executes = [];
    if (txlock) {
      this.executeSql("BEGIN", [], null, function (tx, err) {
        throw new Error("unable to begin transaction: " + err.message);
      });
    }
  }

  SQLitePluginTransaction.prototype.start = function () {
    try {
      this.fn(this);
    } catch (err) {
      // If "fn" throws, we must report the whole transaction as failed.
      this.db.startNextTransaction();
      this.error(err);
      return;
    }
    this.run();
  };

  SQLitePluginTransaction.prototype.executeSql = function (sql, values, success, error) {

    log('called: ' + sql);

    if (this.readOnly && READ_ONLY_REGEX.test(sql)) {
      this.handleStatementFailure(error, {message: 'invalid sql for a read-only transaction'});
      return;
    }

    var qid;
    qid = this.executes.length;
    this.executes.push({
      success: success,
      error: error,
      qid: qid,
      sql: sql,
      params: values || []
    });
  };

  SQLitePluginTransaction.prototype.handleStatementSuccess = function (handler, response) {
    if (!handler) {
      return;
    }
    var rows = response.rows || [];
    var payload = {
      rows: {
        item: function (i) {
          return rows[i];
        },
        length: rows.length
      },
      rowsAffected: response.rowsAffected || 0,
      insertId: response.insertId || void 0
    };
    handler(this, payload);
  };

  SQLitePluginTransaction.prototype.handleStatementFailure = function (handler, response) {
    if (!handler) {
      throw new Error("a statement with no error handler failed: " + response.message);
    }
    if (handler(this, response)) {
      throw new Error("a statement error callback did not return false");
    }
  };

  SQLitePluginTransaction.prototype.run = function () {
    var txFailure = null;
    var tropts = [];
    var batchExecutes = this.executes;
    var waiting = batchExecutes.length;
    this.executes = [];
    var tx = this;
    var handlerFor = function (index, didSucceed) {
      return function (response) {
        var err;
        try {
          if (didSucceed) {
            tx.handleStatementSuccess(batchExecutes[index].success, response);
          } else {
            tx.handleStatementFailure(batchExecutes[index].error, response);
          }
        } catch (_error) {
          err = _error;
          if (!txFailure) {
            txFailure = err;
          }
        }
        if (--waiting === 0) {
          if (txFailure) {
            tx.abort(txFailure);
          } else if (tx.executes.length > 0) {
            // new requests have been issued by the callback
            // handlers, so run another batch.

            tx.run();
          } else {
            tx.finish();
          }
        }
      };
    };
    var mycbmap = {};
    for (var i = 0; i < batchExecutes.length; i++) {
      var request = batchExecutes[i];
      var qid = request.qid;
      mycbmap[qid] = {
        success: handlerFor(i, true),
        error: handlerFor(i, false)
      };
      tropts.push({
        qid: qid,
        query: [request.sql].concat(request.params),
        sql: request.sql,
        params: request.params
      });
    }
    var mycb = function (result) {
      var q, r, res, type, i, len;
      for (i = 0, len = result.length; i < len; i++) {
        r = result[i];
        type = r.type;
        qid = r.qid;
        res = r.result;
        q = mycbmap[qid];
        if (q) {
          if (q[type]) {
            q[type](res);
          }
        }
      }
    };
    if (DEBUG) {
      log('executing: ');
      for (i = 0; i < tropts.length; i++) {
        log('  ' + tropts[i].sql);
      }
    }
    var mycommand = this.db.bg ? "backgroundExecuteSqlBatch" : "executeSqlBatch";
    cordova.exec(mycb, null, "SQLitePlugin", mycommand, [
      {
        dbargs: {
          dbname: this.db.dbname
        },
        executes: tropts
      }
    ]);
  };

  SQLitePluginTransaction.prototype.abort = function (txFailure) {
    if (this.finalized) {
      return;
    }
    var tx = this;
    var succeeded = function (tx) {
      tx.db.startNextTransaction();
      if (tx.error) {
        tx.error(txFailure);
      }
    };
    var failed = function (tx, err) {
      tx.db.startNextTransaction();
      if (tx.error) {
        tx.error(new Error("error while trying to roll back: " + err.message));
      }
    };
    this.finalized = true;
    if (this.txlock) {
      this.executeSql("ROLLBACK", [], succeeded, failed);
      this.run();
    } else {
      succeeded(tx);
    }
  };

  SQLitePluginTransaction.prototype.finish = function () {
    if (this.finalized) {
      return;
    }
    var tx = this;
    var succeeded = function (tx) {
      tx.db.startNextTransaction();
      if (tx.success) {
        tx.success();
      }
    };
    var failed = function (tx, err) {
      tx.db.startNextTransaction();
      if (tx.error) {
        tx.error(new Error("error while trying to commit: " + err.message));
      }
    };
    this.finalized = true;
    if (this.txlock) {
      this.executeSql("COMMIT", [], succeeded, failed);
      this.run();
    } else {
      succeeded(tx);
    }
  };

  // -----------------------------------
  // Globals
  // -----------------------------------

  /*
   FUTURE TBD GONE: Required for db.executePragmStatement() callback ONLY:
   */
  window.SQLitePluginCallback = {
    p1: function (id, result) {
      var mycb;
      log("PRAGMA CB");
      mycb = function () {
        return 1;
      };
      mycb(result);
    }
  };

  function openDatabase() {
    // TODO: this leaks the arguments
    var errorcb, first, okcb, openargs;
    if (arguments.length < 1) {
      return null;
    }
    first = arguments[0];
    openargs = null;
    okcb = null;
    errorcb = null;
    if (first.constructor === String) {
      openargs = {
        name: first
      };
      if (arguments.length >= 5) {
        okcb = arguments[4];
        if (arguments.length > 5) {
          errorcb = arguments[5];
        }
      }
    } else {
      openargs = first;
      if (arguments.length >= 2) {
        okcb = arguments[1];
        if (arguments.length > 2) {
          errorcb = arguments[2];
        }
      }
    }
    return new SQLitePlugin(openargs, okcb, errorcb);
  }


  function deleteDatabase(databaseName, success, error) {
    return cordova.exec(success, error, "SQLitePlugin", "delete", [
      {
        path: databaseName
      }
    ]);
  }

  window.sqlitePlugin = {
    sqliteFeatures: {
      isSQLitePlugin: true
    },
    openDatabase: openDatabase,
    deleteDatabase: deleteDatabase
  };

})();
