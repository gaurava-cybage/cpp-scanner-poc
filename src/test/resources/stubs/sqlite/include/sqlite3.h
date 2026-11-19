/*
 * STUB for the POC only - NOT the real SQLite header.
 *
 * The scan machine has no SQLite SDK, and the fixture solution includes <sqlite3.h> through $(SQLITE_DIR)\include.
 * This file declares the public SQLite C API that the scanner needs to resolve calls (signatures and constant
 * values as documented at https://www.sqlite.org/c3ref/intro.html). It contains no implementation.
 *
 * Use: set SQLITE_DIR to the folder above this "include" folder before running the scanner.
 * The scanner's anchor rule matches on the declaring file name ending in "sqlite3.h", so this stub and the real
 * header behave the same for the scanner. Replace it with the real header when one is available.
 */
#ifndef SQLITE3_H_POC_STUB
#define SQLITE3_H_POC_STUB

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;
typedef long long sqlite3_int64;
typedef void (*sqlite3_destructor_type)(void*);

#define SQLITE_OK            0
#define SQLITE_ERROR         1
#define SQLITE_BUSY          5
#define SQLITE_ROW         100
#define SQLITE_DONE        101

#define SQLITE_INTEGER       1
#define SQLITE_FLOAT         2
#define SQLITE_TEXT          3
#define SQLITE_BLOB          4
#define SQLITE_NULL          5

#define SQLITE_OPEN_READONLY   0x00000001
#define SQLITE_OPEN_READWRITE  0x00000002
#define SQLITE_OPEN_CREATE     0x00000004

#define SQLITE_STATIC      ((sqlite3_destructor_type)0)
#define SQLITE_TRANSIENT   ((sqlite3_destructor_type)-1)

/* Connections */
int sqlite3_open(const char* filename, sqlite3** ppDb);
int sqlite3_open16(const void* filename, sqlite3** ppDb);
int sqlite3_open_v2(const char* filename, sqlite3** ppDb, int flags, const char* zVfs);
int sqlite3_close(sqlite3* db);
int sqlite3_close_v2(sqlite3* db);
const char* sqlite3_errmsg(sqlite3* db);
int sqlite3_changes(sqlite3* db);
sqlite3_int64 sqlite3_last_insert_rowid(sqlite3* db);
void sqlite3_free(void* p);

/* One-step query execution */
int sqlite3_exec(sqlite3* db, const char* sql, int (*callback)(void*, int, char**, char**), void* arg,
                 char** errmsg);

/* Prepared statements */
int sqlite3_prepare(sqlite3* db, const char* zSql, int nByte, sqlite3_stmt** ppStmt, const char** pzTail);
int sqlite3_prepare_v2(sqlite3* db, const char* zSql, int nByte, sqlite3_stmt** ppStmt, const char** pzTail);
int sqlite3_prepare_v3(sqlite3* db, const char* zSql, int nByte, unsigned int prepFlags, sqlite3_stmt** ppStmt,
                       const char** pzTail);
int sqlite3_prepare16(sqlite3* db, const void* zSql, int nByte, sqlite3_stmt** ppStmt, const void** pzTail);
int sqlite3_prepare16_v2(sqlite3* db, const void* zSql, int nByte, sqlite3_stmt** ppStmt, const void** pzTail);
int sqlite3_step(sqlite3_stmt* stmt);
int sqlite3_reset(sqlite3_stmt* stmt);
int sqlite3_finalize(sqlite3_stmt* stmt);
int sqlite3_clear_bindings(sqlite3_stmt* stmt);

/* Binding values */
int sqlite3_bind_int(sqlite3_stmt* stmt, int index, int value);
int sqlite3_bind_int64(sqlite3_stmt* stmt, int index, sqlite3_int64 value);
int sqlite3_bind_double(sqlite3_stmt* stmt, int index, double value);
int sqlite3_bind_null(sqlite3_stmt* stmt, int index);
int sqlite3_bind_text(sqlite3_stmt* stmt, int index, const char* value, int n, sqlite3_destructor_type destructor);

/* Result columns */
int sqlite3_column_count(sqlite3_stmt* stmt);
int sqlite3_column_type(sqlite3_stmt* stmt, int iCol);
int sqlite3_column_int(sqlite3_stmt* stmt, int iCol);
sqlite3_int64 sqlite3_column_int64(sqlite3_stmt* stmt, int iCol);
double sqlite3_column_double(sqlite3_stmt* stmt, int iCol);
const unsigned char* sqlite3_column_text(sqlite3_stmt* stmt, int iCol);

#ifdef __cplusplus
}
#endif

#endif /* SQLITE3_H_POC_STUB */
