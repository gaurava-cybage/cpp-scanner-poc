#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#define SQLITE_OK 0
#define SQLITE_ERROR 1
#define SQLITE_ROW 100
#define SQLITE_DONE 101

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;

int sqlite3_open(const char* filename, sqlite3** ppDb);
int sqlite3_open_v2(const char* filename, sqlite3** ppDb, int flags, const char* zVfs);
int sqlite3_close(sqlite3* db);

int sqlite3_exec(sqlite3* db, const char* sql, void* cb, void* arg, char** errmsg);

int sqlite3_prepare_v2(sqlite3* db, const char* zSql, int nByte, sqlite3_stmt** ppStmt, const char** pzTail);
int sqlite3_prepare(sqlite3* db, const char* zSql, int nByte, sqlite3_stmt** ppStmt, const char** pzTail);
int sqlite3_prepare16(sqlite3* db, const void* zSql, int nByte, sqlite3_stmt** ppStmt, const void** pzTail);
int sqlite3_prepare_v3(sqlite3* db, const char* zSql, int nByte, unsigned int prepFlags, sqlite3_stmt** ppStmt, const char** pzTail);
int sqlite3_prepare16_v2(sqlite3* db, const void* zSql, int nByte, sqlite3_stmt** ppStmt, const void** pzTail);

int sqlite3_step(sqlite3_stmt* pStmt);
int sqlite3_finalize(sqlite3_stmt* pStmt);

int sqlite3_bind_text(sqlite3_stmt* pStmt, int index, const char* value, int n, void(*destructor)(void*));
int sqlite3_bind_double(sqlite3_stmt* pStmt, int index, double value);
int sqlite3_bind_int(sqlite3_stmt* pStmt, int index, int value);

int sqlite3_column_int(sqlite3_stmt* pStmt, int iCol);
double sqlite3_column_double(sqlite3_stmt* pStmt, int iCol);
const char* sqlite3_column_text(sqlite3_stmt* pStmt, int iCol);

#define SQLITE_STATIC ((void(*)(void*))0)

#ifdef __cplusplus
}
#endif
