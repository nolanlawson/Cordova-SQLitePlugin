(function () {
  'use strict';

  var DEFAULT_SIZE = 5000000; // max to avoid popup in safari/ios

  document.addEventListener("deviceready", doAllTests, false);

  function doAllTests() {
    doTest(true, 'Native: ', window.openDatabase);
    doTest(false, 'Plugin: ', window.sqlitePlugin.openDatabase);
  }

  function doTest(native, suiteName, openDatabase) {

    test(suiteName + "db transaction test", function() {

      if (native) {
        ok('skipped');
        return;
      }

      var db = openDatabase({name: "Database", bgType: 1});

      ok(!!db, "db object");

      stop(10);

      db.transaction(function(tx) {

        start(1);
        ok(!!tx, "tx object");

        tx.executeSql('DROP TABLE IF EXISTS test_table');
        tx.executeSql('CREATE TABLE IF NOT EXISTS test_table (id integer primary key, data text, data_num integer)');

        tx.executeSql("INSERT INTO test_table (data, data_num) VALUES (?,?)", ["test", 100], function(tx, res) {
          start(1);
          ok(!!tx, "tx object");
          ok(!!res, "res object");

          console.log("insertId: " + res.insertId + " -- probably 1");
          console.log("rowsAffected: " + res.rowsAffected + " -- should be 1");

          ok(!!res.insertId, "Valid res.insertId");
          equal(res.rowsAffected, 1, "res rows affected");

          db.transaction(function(tx) {
            start(1);
            ok(!!tx, "second tx object");

            tx.executeSql("SELECT count(id) as cnt from test_table;", [], function(tx, res) {
              start(1);

              console.log("res.rows.length: " + res.rows.length + " -- should be 1");
              console.log("res.rows.item(0).cnt: " + res.rows.item(0).cnt + " -- should be 1");

              equal(res.rows.length, 1, "res rows length");
              equal(res.rows.item(0).cnt, 1, "select count");
            });

            tx.executeSql("SELECT data_num from test_table;", [], function(tx, res) {
              start(1);

              equal(res.rows.length, 1, "SELECT res rows length");
              equal(res.rows.item(0).data_num, 100, "SELECT data_num");
            });

            tx.executeSql("UPDATE test_table SET data_num = ? WHERE data_num = 100", [101], function(tx, res) {
              start(1);

              console.log("UPDATE rowsAffected: " + res.rowsAffected + " -- should be 1");

              equal(res.rowsAffected, 1, "UPDATE res rows affected"); /* issue #22 (Android) */
            });

            tx.executeSql("SELECT data_num from test_table;", [], function(tx, res) {
              start(1);

              equal(res.rows.length, 1, "SELECT res rows length");
              equal(res.rows.item(0).data_num, 101, "SELECT data_num");
            });

            tx.executeSql("DELETE FROM test_table WHERE data LIKE 'tes%'", [], function(tx, res) {
              start(1);

              console.log("DELETE rowsAffected: " + res.rowsAffected + " -- should be 1");

              equal(res.rowsAffected, 1, "DELETE res rows affected"); /* issue #22 (Android) */
            });

            tx.executeSql("SELECT data_num from test_table;", [], function(tx, res) {
              start(1);

              equal(res.rows.length, 0, "SELECT res rows length");
            });

          });

        }, function(e) {
          console.log("ERROR: " + e.message);
        });
      }, function(e) {
        console.log("ERROR: " + e.message);
      }, function() {
        console.log("tx success cb");
        ok(true, "tx success cb");
        start(1);
      });

    });

    test(suiteName + "nested transaction test", function() {

      var db = openDatabase("Database2", "1.0", "Demo", DEFAULT_SIZE);

      ok(!!db, "db object");

      stop(3);

      db.transaction(function(tx) {

        start(1);
        ok(!!tx, "tx object");

        tx.executeSql('DROP TABLE IF EXISTS test_table');
        tx.executeSql('CREATE TABLE IF NOT EXISTS test_table (id integer primary key, data text, data_num integer)');

        tx.executeSql("INSERT INTO test_table (data, data_num) VALUES (?,?)", ["test", 100], function(tx, res) {
          start(1);

          console.log("insertId: " + res.insertId + " -- probably 1");
          console.log("rowsAffected: " + res.rowsAffected + " -- should be 1");

          ok(!!res.insertId, "Valid res.insertId");
          equal(res.rowsAffected, 1, "res rows affected");

          tx.executeSql("select count(id) as cnt from test_table;", [], function(tx, res) {
            start(1);

            console.log("res.rows.length: " + res.rows.length + " -- should be 1");
            console.log("res.rows.item(0).cnt: " + res.rows.item(0).cnt + " -- should be 1");

            equal(res.rows.length, 1, "res rows length");
            equal(res.rows.item(0).cnt, 1, "select count");

          });

        });

      });

    });

    function withTestTable(func) {
      stop();
      var db = openDatabase("Database", "1.0", "Demo", DEFAULT_SIZE);
      db.transaction(function(tx) {
        tx.executeSql('DROP TABLE IF EXISTS test_table');
        tx.executeSql('CREATE TABLE IF NOT EXISTS test_table (id integer primary key, data text, data_num integer)');
      }, function(err) { ok(false, err.message) }, function() {
        start();
        func(db);
      });
    };

    test(suiteName + "transaction encompasses all callbacks", function() {

      if (native) {
        ok('skipped');
        return;
      }

      withTestTable(function(db) {
        stop();
        db.transaction(function(tx) {
          tx.executeSql('INSERT INTO test_table (data, data_num) VALUES (?,?)', ['test', 100], function(tx, res) {
            tx.executeSql("SELECT count(*) as cnt from test_table", [], function(tx, res) {
              start();
              equal(res.rows.item(0).cnt, 1, "did insert row");
              stop();
              throw new Error("deliberately aborting transaction");
            });
          });
        }, function(error) {
          start();
          equal(error.message, "deliberately aborting transaction");
          stop();
          db.transaction(function(tx) {
            tx.executeSql("select count(*) as cnt from test_table", [], function(tx, res) {
              start();
              equal(res.rows.item(0).cnt, 0, "final count shows we rolled back");
            });
          });
        }, function() {
          start();
          ok(false, "transaction succeeded but wasn't supposed to");
        });
      });
    });

    test(suiteName + "exception from transaction handler causes failure", function() {

      if (native) {
        ok('skipped');
        return;
      }

      withTestTable(function(db) {
        stop();
        db.transaction(function(tx) {
          throw new Error("boom");
        }, function(err) {
          start();
          equal(err.message, 'boom');
        }, function() {
          ok(false, "not supposed to succeed");
        });
      });
    });

    test(suiteName + "error handler returning true causes rollback", function() {
      withTestTable(function(db) {
        stop(2);
        db.transaction(function(tx) {
          tx.executeSql("insert into test_table (data, data_num) VALUES (?,?)", ['test', null], function(tx, res) {
            start();
            equal(res.rowsAffected, 1, 'row inserted');
            stop();
            tx.executeSql("select * from bogustable", [], function(tx, res) {
              start();
              ok(false, "select statement not supposed to succeed");
            }, function(tx, err) {
              start();
              ok(!!err.message, "should report a valid error message");
              return true;
            });
          });
        }, function(err) {
          start();
          ok(!!err.message, "should report error message");
          stop();
          db.transaction(function(tx) {
            tx.executeSql("select count(*) as cnt from test_table", [], function(tx, res) {
              start();
              equal(res.rows.item(0).cnt, 0, "should have rolled back");
            });
          });
        }, function() {
          start();
          ok(false, "not supposed to succeed");
        });
      });
    });

    test(suiteName + "error handler returning non-true lets transaction continue", function() {
      withTestTable(function(db) {
        stop(2);
        db.transaction(function(tx) {
          tx.executeSql("insert into test_table (data, data_num) VALUES (?,?)", ['test', null], function(tx, res) {
            start();
            equal(res.rowsAffected, 1, 'row inserted');
            stop();
            tx.executeSql("select * from bogustable", [], function(tx, res) {
              start();
              ok(false, "select statement not supposed to succeed");
            }, function(tx, err) {
              start();
              ok(!!err.message, "should report a valid error message");
            });
          });
        }, function(err) {
          start();
          ok(false, "transaction was supposed to succeed");
        }, function() {
          db.transaction(function(tx) {
            tx.executeSql("select count(*) as cnt from test_table", [], function(tx, res) {
              start();
              equal(res.rows.item(0).cnt, 1, "should have commited");
            });
          });
        });
      });
    });

    test(suiteName + "missing error handler causes rollback", function() {
      withTestTable(function(db) {
        stop();
        db.transaction(function(tx) {
          tx.executeSql("insert into test_table (data, data_num) VALUES (?,?)", ['test', null], function(tx, res) {
            equal(res.rowsAffected, 1, 'row inserted');
            tx.executeSql("select * from bogustable", [], function(tx, res) {
              ok(false, "select statement not supposed to succeed");
            });
          });
        }, function(err) {
          ok(!!err.message, "should report a valid error message");
          db.transaction(function(tx) {
            tx.executeSql("select count(*) as cnt from test_table", [], function(tx, res) {
              start();
              equal(res.rows.item(0).cnt, 0, "should have rolled back");
            });
          });
        }, function() {
          start();
          ok(false, "transaction was supposed to fail");
        });
      });
    });

    test(suiteName + "all columns should be included in result set (including 'null' columns)", function() {
      withTestTable(function(db) {
        stop();
        db.transaction(function(tx) {
          tx.executeSql("insert into test_table (data, data_num) VALUES (?,?)", ["test", null], function(tx, res) {
            equal(res.rowsAffected, 1, "row inserted");
            tx.executeSql("select * from test_table", [], function(tx, res) {
              var row = res.rows.item(0);
              deepEqual(row, { id: 1, data: "test", data_num: null }, "all columns should be included in result set.");
              start();
            });
          });
        });
      });
    });

    test(suiteName + "number values inserted using number bindings", function() {
      stop();
      var db = openDatabase("DB", "1.0", "Demo", DEFAULT_SIZE);
      db.transaction(function(tx) {
        tx.executeSql('DROP TABLE IF EXISTS test_table');
        tx.executeSql('CREATE TABLE IF NOT EXISTS test_table (id integer primary key, data_text, data_int, data_real)');
      }, function(err) { ok(false, err.message) }, function() {
        db.transaction(function(tx) {
          // create columns with no type affinity
          tx.executeSql("insert into test_table (data_text, data_int, data_real) VALUES (?,?,?)", ["3.14159", 314159, 3.14159], function(tx, res) {
            equal(res.rowsAffected, 1, "row inserted");
            tx.executeSql("select * from test_table", [], function(tx, res) {
              start();
              var row = res.rows.item(0);
              strictEqual(row.data_text, "3.14159", "data_text should have inserted data as text");
              strictEqual(row.data_int, 314159, "data_int should have inserted data as an integer");
              ok(Math.abs(row.data_real - 3.14159) < 0.000001, "data_real should have inserted data as a real");
            });
          });
        });
      });
    });

    test(suiteName + "readTransaction should throw on modification", function() {
      stop();
      var db = openDatabase("Database-readonly", "1.0", "Demo", DEFAULT_SIZE);
      db.transaction(function(tx) {
        tx.executeSql('DROP TABLE IF EXISTS test_table');
        tx.executeSql('CREATE TABLE IF NOT EXISTS test_table (foo text)');
        tx.executeSql('INSERT INTO test_table VALUES ("bar")');
      }, function () {}, function () {
        db.readTransaction(function (tx) {
          tx.executeSql('SELECT * from test_table', [], function (tx, res) {
            equal(res.rows.length, 1);
            equal(res.rows.item(0).foo, 'bar');
          });
        }, function () {}, function () {
          var tasks;
          var numDone = 0;
          var failed = false;
          function checkDone() {
            if (++numDone === tasks.length) {
              start();
            }
          }
          function fail() {
            if (!failed) {
              failed = true;
              start();
              ok(false, 'readTransaction was supposed to fail');
            }
          }
          // all of these should throw an error
          tasks = [
            function () {
              db.readTransaction(function (tx) {
                tx.executeSql('DELETE from test_table');
              }, checkDone, fail);
            },
            function () {
              db.readTransaction(function (tx) {
                tx.executeSql('UPDATE test_table SET foo = "baz"');
              }, checkDone, fail);
            },
            function () {
              db.readTransaction(function (tx) {
                tx.executeSql('INSERT INTO test_table VALUES ("baz")');
              }, checkDone, fail);
            },
            function () {
              db.readTransaction(function (tx) {
                tx.executeSql('DROP TABLE test_table');
              }, checkDone, fail);
            },
            function () {
              db.readTransaction(function (tx) {
                tx.executeSql('CREATE TABLE test_table2');
              }, checkDone, fail);
            }
          ];
          for (var i = 0; i < tasks.length; i++) {
            tasks[i]();
          }
        });
      });
    });


  /**
   test(suiteName + "PRAGMA & multiple databases", function() {
          var db = openDatabase("DB1", "1.0", "Demo", DEFAULT_SIZE);

          var db2 = openDatabase("DB2", "1.0", "Demo", DEFAULT_SIZE);

          db.transaction(function(tx) {
            tx.executeSql('DROP TABLE IF EXISTS test_table');
            tx.executeSql('CREATE TABLE IF NOT EXISTS test_table (id integer primary key, data text, data_num integer)', [], function() {
              console.log("test_table created");
            });

            stop();
            db.executeSql("pragma table_info (test_table);", [], function(res) {
              start();
              console.log("PRAGMA res: " + JSON.stringify(res));
              equal(res.rows.item(2).name, "data_num", "DB1 table number field name");
            });
          });

          stop(2);
          db2.transaction(function(tx) {
            tx.executeSql('DROP TABLE IF EXISTS tt2');
            tx.executeSql('CREATE TABLE IF NOT EXISTS tt2 (id2 integer primary key, data2 text, data_num2 integer)', [], function() {
              console.log("tt2 created");
            });

            db.executeSql("pragma table_info (test_table);", [], function(res) {
              start();
              console.log("PRAGMA (db) res: " + JSON.stringify(res));
              equal(res.rows.item(0).name, "id", "DB1 table key field name");
              equal(res.rows.item(1).name, "data", "DB1 table text field name");
              equal(res.rows.item(2).name, "data_num", "DB1 table number field name");
            });

            db2.executeSql("pragma table_info (tt2);", [], function(res) {
              start();
              console.log("PRAGMA (tt2) res: " + JSON.stringify(res));
              equal(res.rows.item(0).name, "id2", "DB2 table key field name");
              equal(res.rows.item(1).name, "data2", "DB2 table text field name");
              equal(res.rows.item(2).name, "data_num2", "DB2 table number field name");
            });
          });

        });

   var db;
   module("Error codes", {
          setup: function() {
            stop();
            db = openDatabase("Database", "1.0", "Demo", DEFAULT_SIZE);
            db.transaction(function(tx) {
              tx.executeSql('DROP TABLE IF EXISTS test_table');
              tx.executeSql('CREATE TABLE IF NOT EXISTS test_table (data unique)');
            }, function(error) {
              ok(false, error.message);
              start();
            }, function() {
              start();
            });
          },
          teardown: function() {
            stop();
            db.transaction(function(tx) {
              tx.executeSql('DROP TABLE IF EXISTS test_table');
            }, function(error) {
              ok(false, error.message);
              start();
            }, function() {
              start();
            });
          }
        });

   test(suiteName + "syntax error", function() {
          stop(2);
          db.transaction(function(tx) {
            // This insertion has a sql syntax error
            tx.executeSql("insert into test_table (data) VALUES ", [123], function(tx) {
              ok(false, "unexpected success");
              start();
            }, function(tx, error) {
              strictEqual(error.code, SQLException.SYNTAX_ERR, "error.code === SYNTAX_ERR");
              equal(error.message, "Request failed: insert into test_table (data) VALUES ,123", "error.message");
              start();

              // We want this error to fail the entire transaction
              return true;
            });
          }, function (error) {
            strictEqual(error.code, SQLException.SYNTAX_ERR, "error.code === SYNTAX_ERR");
            equal(error.message, "Request failed: insert into test_table (data) VALUES ,123", "error.message");
            start();
          });
        });

   test(suiteName + "constraint violation", function() {
          stop(2);
          db.transaction(function(tx) {
            tx.executeSql("insert into test_table (data) VALUES (?)", [123], null, function(tx, error) {
              ok(false, error.message);
            });

            // This insertion will violate the unique constraint
            tx.executeSql("insert into test_table (data) VALUES (?)", [123], function(tx) {
              ok(false, "unexpected success");
              start();
            }, function(tx, error) {
              strictEqual(error.code, SQLException.CONSTRAINT_ERR, "error.code === CONSTRAINT_ERR");
              equal(error.message, "Request failed: insert into test_table (data) VALUES (?),123", "error.message");
              start();

              // We want this error to fail the entire transaction
              return true;
            });
          }, function(error) {
            strictEqual(error.code, SQLException.CONSTRAINT_ERR, "error.code === CONSTRAINT_ERR");
            equal(error.message, "Request failed: insert into test_table (data) VALUES (?),123", "error.message");
            start();
          });
        });
   **/
  }
})();