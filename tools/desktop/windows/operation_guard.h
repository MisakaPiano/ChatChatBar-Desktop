/* Bounded operation admission; included only by directory_guard.c. Read-only OS/MSI
 * observations except the early, pre-cost INSTALLDIR resolution. Not a security
 * boundary against an administrator or a same-user author modifying both MSI tables
 * and their authored contract. No component/client inventory enumeration. */
#include <sddl.h>
#include <shlobj.h>
#include <knownfolders.h>
#include <bcrypt.h>
#include <stdlib.h>

static __declspec(thread) MSIHANDLE operation_diagnostic_install;
static NTSTATUS guard_nt(const wchar_t *api,NTSTATUS value) {
    guard_code(api,(UINT)value,0,0,3);return value;
}
static NTSTATUS WINAPI guard_hash_provider(BCRYPT_ALG_HANDLE*a,LPCWSTR b,LPCWSTR c,ULONG d){return guard_nt(L"BCryptOpenAlgorithmProvider",BCryptOpenAlgorithmProvider(a,b,c,d));}
static NTSTATUS WINAPI guard_hash_create(BCRYPT_ALG_HANDLE a,BCRYPT_HASH_HANDLE*b,PUCHAR c,ULONG d,PUCHAR e,ULONG f,ULONG g){return guard_nt(L"BCryptCreateHash",BCryptCreateHash(a,b,c,d,e,f,g));}
static NTSTATUS WINAPI guard_hash_data(BCRYPT_HASH_HANDLE a,PUCHAR b,ULONG c,ULONG d){return guard_nt(L"BCryptHashData",BCryptHashData(a,b,c,d));}
static NTSTATUS WINAPI guard_hash_finish(BCRYPT_HASH_HANDLE a,PUCHAR b,ULONG c,ULONG d){return guard_nt(L"BCryptFinishHash",BCryptFinishHash(a,b,c,d));}
#define BCryptOpenAlgorithmProvider guard_hash_provider
#define BCryptCreateHash guard_hash_create
#define BCryptHashData guard_hash_data
#define BCryptFinishHash guard_hash_finish
#ifdef CCB_GUARD_TEST
typedef void (__stdcall *HeaderDiagnosticSink)(const wchar_t *);
static __declspec(thread) HeaderDiagnosticSink header_diagnostic_sink;
#endif
static void header_failure_log(const GuardHeaderTrace *trace) {
    wchar_t line[768];GuardHeaderTrace *saved=guard_header_trace;guard_header_trace=NULL;
    StringCchPrintfW(line,ARRAYSIZE(line),L"CCB_HEADER_REJECT phase=%s check=%s group=%u group_ordinal=%u ordinal=%u last_api=%s last_kind=%u last_code=%u last_field=%u first_api=%s first_kind=%u first_code=%u first_field=%u",
        trace->phase?trace->phase:L"NONE",trace->check?trace->check:L"NONE",trace->group,trace->group_ordinal,trace->ordinal,
        trace->last_api?trace->last_api:L"NONE",trace->last_kind,trace->last_code,trace->last_field,
        trace->first_api?trace->first_api:L"NONE",trace->first_kind,trace->first_code,trace->first_field);
#ifdef CCB_GUARD_TEST
    if(header_diagnostic_sink)header_diagnostic_sink(line);
    else
#endif
    if(operation_diagnostic_install)real_log((void *)(UINT_PTR)operation_diagnostic_install,line);
    guard_header_trace=saved;
}
static void header_phase(const wchar_t *phase) {
    if(guard_header_trace){ZeroMemory(guard_header_trace,sizeof(*guard_header_trace));guard_header_trace->phase=phase;guard_header_trace->check=phase;}
}

typedef struct { BCRYPT_ALG_HANDLE alg; BCRYPT_HASH_HANDLE hash; } OpHash;
static void operation_status(const wchar_t *stage,UINT status) {
    wchar_t line[192];if(!operation_diagnostic_install)return;
    StringCchPrintfW(line,192,L"CCB_OPERATION_DETAIL stage=%s status=%u",stage,status);
    real_log((void *)(UINT_PTR)operation_diagnostic_install,line);
}
static int costing_component_row(MSIHANDLE db,MSIHANDLE row) {
    wchar_t id[CAP],code[CAP],dir[CAP],condition[CAP],key[CAP],original[CAP],query[CAP],value[CAP];
    MSIHANDLE view=0,base=0,extra=0;size_t n,i;int ok=0;UINT status;
    if(!field(row,1,id)||!field(row,2,code)||wcscmp(code,L"Temporary Id")||!field(row,3,dir)||wcscmp(dir,L"WindowsFolder")||
        MsiRecordGetInteger(row,4)!=260||!field(row,5,condition)||condition[0]||!field(row,6,key)||key[0])return 0;
    n=wcslen(id);if(n<5||wcsncmp(id,L"__",2)||wcscmp(id+n-2,L"65")||StringCchCopyNW(original,CAP,id+2,n-4)!=S_OK)return 0;
    for(i=0;original[i];i++)if(!(iswalnum(original[i])||original[i]==L'_'||original[i]==L'.'))return 0;
    if(StringCchPrintfW(query,CAP,L"SELECT `ComponentId`,`Attributes`,`Condition`,`KeyPath` FROM `Component` WHERE `Component`='%s'",original)!=S_OK||
        MsiDatabaseOpenViewW(db,query,&view)||MsiViewExecute(view,0)||MsiViewFetch(view,&base)||!field(base,1,value)||!guid(value)||
        MsiRecordGetInteger(base,2)!=260||!field(base,3,value)||value[0]||!field(base,4,value)||!value[0]||MsiViewFetch(view,&extra)!=ERROR_NO_MORE_ITEMS)goto done;
    MsiCloseHandle(base);base=0;MsiCloseHandle(view);view=0;
    { const wchar_t *tables[]={L"File",L"Registry",L"FeatureComponents"};DWORD t;
      for(t=0;t<ARRAYSIZE(tables);t++) {
        if(StringCchPrintfW(query,CAP,L"SELECT `Component_` FROM `%s` WHERE `Component_`='%s'",tables[t],id)!=S_OK||MsiDatabaseOpenViewW(db,query,&view)||MsiViewExecute(view,0))goto done;
        status=MsiViewFetch(view,&base);if(status!=ERROR_NO_MORE_ITEMS)goto done;
        MsiCloseHandle(view);view=0;
      }
    }
    ok=1;
done:if(extra)MsiCloseHandle(extra);if(base)MsiCloseHandle(base);if(view)MsiCloseHandle(view);return ok;
}
static int costing_component_id(MSIHANDLE db,const wchar_t *id) {
    MSIHANDLE view=0,row=0;wchar_t query[CAP];int ok=0;
    if(wcschr(id,L'\'')||StringCchPrintfW(query,CAP,L"SELECT * FROM `Component` WHERE `Component`='%s'",id)!=S_OK||
        MsiDatabaseOpenViewW(db,query,&view)||MsiViewExecute(view,0)||MsiViewFetch(view,&row))goto done;
    ok=costing_component_row(db,row);
done:if(row)MsiCloseHandle(row);if(view)MsiCloseHandle(view);return ok;
}
static int costing_directory_row(MSIHANDLE row) {
    wchar_t a[CAP],b[CAP],c[CAP];return field(row,1,a)&&!wcscmp(a,L"WindowsFolder")&&field(row,2,b)&&!wcscmp(b,L"TARGETDIR")&&field(row,3,c)&&!wcscmp(c,L"?");
}
static int hash_open(OpHash *h) {
    ZeroMemory(h, sizeof(*h));
    return BCryptOpenAlgorithmProvider(&h->alg, BCRYPT_SHA256_ALGORITHM, NULL, 0) == 0 &&
        BCryptCreateHash(h->alg, &h->hash, NULL, 0, NULL, 0, 0) == 0;
}
static void hash_close(OpHash *h) {
    if (h->hash) BCryptDestroyHash(h->hash);
    if (h->alg) BCryptCloseAlgorithmProvider(h->alg, 0);
}
static int hash_add(OpHash *h, const void *data, ULONG bytes) { return BCryptHashData(h->hash, (PUCHAR)data, bytes, 0) == 0; }
static int hash_finish(OpHash *h, wchar_t result[65]) {
    BYTE bytes[32]; DWORD i; static const wchar_t digits[] = L"0123456789abcdef";
    if (BCryptFinishHash(h->hash, bytes, sizeof(bytes), 0) != 0) return 0;
    for (i = 0; i < 32; i++) { result[i*2] = digits[bytes[i] >> 4]; result[i*2+1] = digits[bytes[i] & 15]; }
    result[64] = 0; return 1;
}
static int compare_rows(const void *a, const void *b) { return wcscmp(*(const wchar_t *const *)a, *(const wchar_t *const *)b); }
static int table_digest(MSIHANDLE db, const wchar_t *table, DWORD width, DWORD expected, const wchar_t *digest) {
    wchar_t query[CAP], value[CAP], prefix[32], calculated[65]; wchar_t **rows = NULL;
    DWORD count = 0, i, n, generated=0; UINT status = ERROR_FUNCTION_FAILED; MSIHANDLE view = 0, row = 0; OpHash hash = {0}; int ok = 0;
    guard_check(L"TABLE_HASH_SHAPE",0);
    if (!width || width > 16 || expected > 4096 || wcschr(table, L'`') || wcslen(table) > 72) return 0;
    guard_check(L"TABLE_HASH_ALLOCATION_OPEN",0);
    rows = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, (expected + 1) * sizeof(*rows));
    if (!rows || StringCchPrintfW(query, CAP, L"SELECT * FROM `%s`", table) != S_OK ||
        MsiDatabaseOpenViewW(db, query, &view) != ERROR_SUCCESS || MsiViewExecute(view, 0) != ERROR_SUCCESS) goto done;
    while ((status = MsiViewFetch(view, &row)) == ERROR_SUCCESS) {
        guard_check(L"TABLE_HASH_ROW",count+1);
        /* FileCost creates one resource-free registry-cost projection per authored
         * HKCU64 component and its WindowsFolder cost directory. Original rows must
         * still match the complete sealed hash. Unknown projections are not skipped. */
        if((!wcscmp(table,L"Component")&&costing_component_row(db,row))||(!wcscmp(table,L"Directory")&&costing_directory_row(row))) {
            if(++generated>expected)goto done;MsiCloseHandle(row);row=0;continue;
        }
        if (GUARD_AT("TABLE_HASH_ROW_LIMIT",count+1,count >= expected)) goto done;
        guard_check(L"TABLE_HASH_ROW_ALLOCATION",count+1);
        rows[count] = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, 16384 * sizeof(wchar_t));
        if (!rows[count]) goto done;
        count++;
        for (i = 1; i <= width; i++) {
            guard_check(L"TABLE_HASH_FIELD",count);
            if (!field(row, i, value)) goto done;
            n = (DWORD)wcslen(value);
            if (StringCchPrintfW(prefix, 32, L"%lu:", n) != S_OK || StringCchCatW(rows[count-1], 16384, prefix) != S_OK ||
                StringCchCatW(rows[count-1], 16384, value) != S_OK) goto done;
        }
        MsiCloseHandle(row); row = 0;
    }
    if (GUARD_AT("TABLE_HASH_FETCH_END",count,status != ERROR_NO_MORE_ITEMS) || GUARD_AT("TABLE_HASH_ROW_COUNT",count,count != expected) || !GUARD_AT("TABLE_HASH_OPEN",count,hash_open(&hash))) goto done;
    qsort(rows, count, sizeof(*rows), compare_rows);
    for (i = 0; i < count; i++) if (!GUARD_AT("TABLE_HASH_DATA",i+1,hash_add(&hash, rows[i], (ULONG)(wcslen(rows[i]) * sizeof(wchar_t)))) || !GUARD_AT("TABLE_HASH_SEPARATOR",i+1,hash_add(&hash, L"\n", 2))) goto done;
    if (!GUARD_AT("TABLE_HASH_FINISH",count,hash_finish(&hash, calculated)) || !GUARD_AT("TABLE_HASH_MATCH",count,same(calculated, digest))) goto done;
    ok = 1;
done:
    if(!ok && operation_diagnostic_install) { wchar_t line[256];StringCchPrintfW(line,256,L"CCB_OPERATION_TABLE_REJECT table=%s authored_rows=%lu observed_rows=%lu",table,expected,count);real_log((void *)(UINT_PTR)operation_diagnostic_install,line); }
    hash_close(&hash);
    if (row) MsiCloseHandle(row);
    if (view) MsiCloseHandle(view);
    if (rows) { for (i = 0; i < count; i++) if (rows[i]) HeapFree(GetProcessHeap(), 0, rows[i]); HeapFree(GetProcessHeap(), 0, rows); }
    return ok;
}
static int table_known(const wchar_t *name) {
    static const wchar_t *names[] = {L"Property",L"Directory",L"Component",L"File",L"Registry",L"Feature",L"FeatureComponents",L"RemoveFile",L"CreateFolder",
        L"Shortcut",L"Icon",L"MsiShortcutProperty",L"AppSearch",L"RegLocator",L"Signature",L"Upgrade",L"CustomAction",L"InstallExecuteSequence",L"InstallUISequence",
        L"AdminExecuteSequence",L"AdminUISequence",L"AdvtExecuteSequence",L"Media",L"MsiFileHash",L"Binary",L"WixRemoveFolderEx"};
    DWORD i; for (i = 0; i < ARRAYSIZE(names); i++) if (!wcscmp(name, names[i])) return 1; return 0;
}
static int table_meta(const wchar_t *name) {
    return !wcscmp(name,L"_Validation") || !wcscmp(name,L"_Tables") || !wcscmp(name,L"_Columns") || !wcscmp(name,L"_Streams") ||
        !wcscmp(name,L"_Storages") || !wcscmp(name,L"CcbOperationTable") || !wcscmp(name,L"CcbOperationStream");
}
/* Installer adds these nonpersistent implementation tables when opening/costing a
 * session. They are not authored resource tables. Names, persistence and exact
 * column types are checked; a persistent imitation or unknown table still fails. */
static int runtime_table(MSIHANDLE db,const wchar_t *name) {
    static const wchar_t *property_names[]={L"",L""},*property_types[]={L"g0",L"g0"};
    static const wchar_t *folder_names[]={L"FolderId",L"FolderPath",L""},*folder_types[]={L"j2",L"g0",L"O0"};
    static const wchar_t *cost_names[]={L"",L""},*cost_types[]={L"j4",L"O0"};
    const wchar_t **names,**types;DWORD width,i;MSIHANDLE view=0,record=0;wchar_t query[CAP],value[CAP];int ok=0;
    if(!wcscmp(name,L"_Property")) { names=property_names;types=property_types;width=2; }
    else if(!wcscmp(name,L"#_FolderCache")) { names=folder_names;types=folder_types;width=3; }
    else if(!wcscmp(name,L"CostAdjuster")) { names=cost_names;types=cost_types;width=2; }
    else return 0;
    if(MsiDatabaseIsTablePersistentW(db,name)!=MSICONDITION_FALSE||StringCchPrintfW(query,CAP,L"SELECT * FROM `%s`",name)!=S_OK||MsiDatabaseOpenViewW(db,query,&view))goto done;
    if(MsiViewGetColumnInfo(view,MSICOLINFO_NAMES,&record)||MsiRecordGetFieldCount(record)!=width)goto done;
    for(i=0;i<width;i++)if(!field(record,i+1,value)||wcscmp(value,names[i]))goto done;
    MsiCloseHandle(record);record=0;
    if(MsiViewGetColumnInfo(view,MSICOLINFO_TYPES,&record)||MsiRecordGetFieldCount(record)!=width)goto done;
    for(i=0;i<width;i++)if(!field(record,i+1,value)||wcscmp(value,types[i]))goto done;
    ok=1;
done:if(record)MsiCloseHandle(record);if(view)MsiCloseHandle(view);return ok;
}
static int stream_contract(MSIHANDLE db) {
    MSIHANDLE view=0,row=0,data_view=0,data_row=0; wchar_t table[CAP],name[CAP],digest[CAP],query[CAP],actual[65];
    BYTE buffer[65536]; DWORD length,total,count=0; UINT status; int ok=0; OpHash h={0};
    guard_group(2,0);guard_check(L"STREAM_CONTRACT_OPEN",0);
    if (MsiDatabaseOpenViewW(db,L"SELECT * FROM `CcbOperationStream`",&view) || MsiViewExecute(view,0)) goto done;
    while ((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        guard_group(2,count+1);guard_check(L"STREAM_CONTRACT_ROW",count+1);
        if (++count>32 || !field(row,1,table) || !(same(table,L"Binary") || same(table,L"Icon")) || !field(row,2,name) ||
            wcschr(name,L'\'') || !field(row,4,digest) || !hash_open(&h)) goto done;
        guard_check(L"STREAM_DATA_OPEN",count);
        if (StringCchPrintfW(query,CAP,L"SELECT `Data` FROM `%s` WHERE `Name`='%s'",table,name)!=S_OK ||
            MsiDatabaseOpenViewW(db,query,&data_view) || MsiViewExecute(data_view,0) || MsiViewFetch(data_view,&data_row)) goto done;
        total=0;
        do { guard_check(L"STREAM_DATA_READ",count);length=sizeof(buffer); if (MsiRecordReadStream(data_row,1,(char *)buffer,&length)) goto done;
             total+=length; if (GUARD_AT("STREAM_DATA_LIMIT",count,total>8*1024*1024) || !GUARD_AT("STREAM_HASH_DATA",count,hash_add(&h,buffer,length))) goto done;
        } while (length);
        if (GUARD_AT("STREAM_LENGTH_SIGN",count,MsiRecordGetInteger(row,3))<0 || GUARD_AT("STREAM_LENGTH_MATCH",count,total!=(DWORD)MsiRecordGetInteger(row,3)) || !GUARD_AT("STREAM_HASH_FINISH",count,hash_finish(&h,actual)) || !GUARD_AT("STREAM_HASH_MATCH",count,same(actual,digest))) goto done;
        hash_close(&h); ZeroMemory(&h,sizeof(h)); MsiCloseHandle(data_row); data_row=0; MsiCloseHandle(data_view); data_view=0; MsiCloseHandle(row); row=0;
    }
    if(GUARD_AT("STREAM_FETCH_END",count,status!=ERROR_NO_MORE_ITEMS) || !GUARD_AT("STREAM_COUNT",count,count))goto done;
    MsiCloseHandle(view);view=0;
    { const wchar_t *tables[]={L"Binary",L"Icon"};DWORD t,actual_count=0;
      for(t=0;t<ARRAYSIZE(tables);t++) {
        guard_group(4,t+1);guard_check(L"STREAM_TABLE_PERSISTENCE",0);
        if(MsiDatabaseIsTablePersistentW(db,tables[t])!=MSICONDITION_TRUE)continue;
        if(StringCchPrintfW(query,CAP,L"SELECT `Name` FROM `%s`",tables[t])!=S_OK||MsiDatabaseOpenViewW(db,query,&view)||MsiViewExecute(view,0))goto done;
        while((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
          MSIHANDLE v=0,r=0;int present=0;
          guard_check(L"STREAM_BACKMAP_ROW",actual_count+1);
          if(++actual_count>32||!field(row,1,name)||wcschr(name,L'\'')||StringCchPrintfW(query,CAP,L"SELECT `Digest` FROM `CcbOperationStream` WHERE `TableName`='%s' AND `StreamName`='%s'",tables[t],name)!=S_OK)goto done;
          if(!MsiDatabaseOpenViewW(db,query,&v)&&!MsiViewExecute(v,0)&&!MsiViewFetch(v,&r))present=1;
          if(r)MsiCloseHandle(r);if(v)MsiCloseHandle(v);if(!GUARD_AT("STREAM_BACKMAP_MISSING",actual_count,present))goto done;
          MsiCloseHandle(row);row=0;
        }
        if(GUARD_AT("STREAM_BACKMAP_FETCH_END",actual_count,status!=ERROR_NO_MORE_ITEMS))goto done;MsiCloseHandle(view);view=0;
      }
      ok=GUARD_AT("STREAM_BACKMAP_COUNT",actual_count,actual_count==count);
    }
done:
    SecureZeroMemory(buffer,sizeof(buffer)); hash_close(&h);
    if (data_row) MsiCloseHandle(data_row); if (data_view) MsiCloseHandle(data_view);
    if (row) MsiCloseHandle(row); if (view) MsiCloseHandle(view); return ok;
}
static int sealed_tables(MSIHANDLE db) {
    MSIHANDLE view=0,row=0; wchar_t name[CAP],digest[CAP],marker[CAP],seen[64][73]; DWORD count=0,i,walked=0; UINT status; int ok=0;
    guard_group(1,0);guard_check(L"TABLE_CONTRACT_OPEN",0);
    if (!db_property(db,L"CCB_OPERATION_CONTRACT",marker) || !same(marker,L"1") ||
        MsiDatabaseOpenViewW(db,L"SELECT * FROM `CcbOperationTable`",&view) || MsiViewExecute(view,0)) goto done;
    while ((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        guard_group(1,count+1);guard_check(L"TABLE_CONTRACT_ROW",count+1);
        if (count>=64 || !field(row,1,name) || !table_known(name) || !wcscmp(name,L"Binary") || !wcscmp(name,L"Icon") ||
            !field(row,4,digest) || !table_digest(db,name,(DWORD)MsiRecordGetInteger(row,2),(DWORD)MsiRecordGetInteger(row,3),digest) ||
            StringCchCopyW(seen[count++],73,name)!=S_OK) goto done;
        MsiCloseHandle(row); row=0;
    }
    if (GUARD_AT("TABLE_CONTRACT_FETCH_END",count,status!=ERROR_NO_MORE_ITEMS) || GUARD_AT("TABLE_CONTRACT_COUNT",count,count<16)) goto done;
    MsiCloseHandle(view); view=0;
    guard_group(3,0);guard_check(L"ACTUAL_TABLE_LIST_OPEN",0);
    if (MsiDatabaseOpenViewW(db,L"SELECT `Name` FROM `_Tables`",&view) || MsiViewExecute(view,0)) goto done;
    while ((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        guard_group(3,++walked);guard_check(L"ACTUAL_TABLE_LIST_ROW",walked);
        if (!field(row,1,name)) goto done;
        if (!table_meta(name) && wcscmp(name,L"Binary") && wcscmp(name,L"Icon") && !runtime_table(db,name)) {
            for (i=0;i<count;i++) if (!wcscmp(name,seen[i])) break;
            if (i==count) { if(operation_diagnostic_install) { wchar_t line[192];StringCchPrintfW(line,192,L"CCB_OPERATION_UNKNOWN_TABLE table=%s",name);real_log((void *)(UINT_PTR)operation_diagnostic_install,line); } goto done; }
        }
        MsiCloseHandle(row); row=0;
    }
    ok=GUARD_AT("ACTUAL_TABLE_LIST_END",walked,status==ERROR_NO_MORE_ITEMS) && stream_contract(db);
done:
    if (row) MsiCloseHandle(row); if (view) MsiCloseHandle(view); return ok;
}

/* A single explicit effective identity. RegOpenCurrentUser respects impersonation;
 * no cached HKEY_CURRENT_USER and no cross-user product/consumer enumeration. */
typedef struct {
    Api api; MSIHANDLE db; HKEY user; HANDLE token; wchar_t *sid;
    wchar_t product[CAP],name[CAP],ver[CAP],upgrade[CAP],local[CAP],windows[CAP],root[CAP],old[CAP],old_ver[CAP];
    int self_present; wchar_t operation;
} Operation;
static int context_open(Operation *o) {
    BYTE buffer[2048]; DWORD size; PWSTR known=NULL; wchar_t session[CAP],normalized[CAP]; int ok=0;
    if (!OpenThreadToken(GetCurrentThread(),TOKEN_QUERY|TOKEN_IMPERSONATE|TOKEN_DUPLICATE,TRUE,&o->token)) {
        if (GetLastError()!=ERROR_NO_TOKEN) { operation_status(L"THREAD_TOKEN",GetLastError());return 0; }
        if (!OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY|TOKEN_IMPERSONATE|TOKEN_DUPLICATE,&o->token)) { operation_status(L"PROCESS_TOKEN",GetLastError());return 0; }
    }
    if (!GetTokenInformation(o->token,TokenUser,buffer,sizeof(buffer),&size) ||
        !ConvertSidToStringSidW(((TOKEN_USER *)buffer)->User.Sid,&o->sid) ||
        RegOpenCurrentUser(KEY_READ|KEY_WOW64_64KEY,&o->user)!=ERROR_SUCCESS) goto done;
    if (SHGetKnownFolderPath(&FOLDERID_LocalAppData,KF_FLAG_DONT_VERIFY,o->token,&known)!=S_OK ||
        !canonical(known,o->local) || !property(&o->api,L"LocalAppDataFolder",session) || !canonical(session,normalized) || !same(o->local,normalized)) goto done;
    if (!GetWindowsDirectoryW(session,CAP) || !canonical(session,o->windows) || !property(&o->api,L"WindowsFolder",session) ||
        !canonical(session,normalized) || !same(o->windows,normalized)) goto done;
    ok=1;
done:
    if (known) CoTaskMemFree(known); return ok;
}
static int info(Operation *o,const wchar_t *code,const wchar_t *key,wchar_t *out) {
    DWORD size=CAP; UINT status=MsiGetProductInfoExW(code,o->sid,MSIINSTALLCONTEXT_USERUNMANAGED,key,out,&size);
    if (status!=ERROR_SUCCESS) { wchar_t line[128]; StringCchPrintfW(line,128,L"CCB_OPERATION_QUERY status=%u",status); real_log(o->api.context,line); }
    return status==ERROR_SUCCESS;
}
static int product_state_result(UINT status,const wchar_t *value) {
    if(status==ERROR_UNKNOWN_PRODUCT)return ABSENT;
    return status==ERROR_SUCCESS && same(value,L"5")?PRESENT:LOOKUP_FAILED;
}
static int product_present(Operation *o,const wchar_t *code) {
    wchar_t value[CAP]; DWORD size=CAP; UINT status=MsiGetProductInfoExW(code,o->sid,MSIINSTALLCONTEXT_USERUNMANAGED,INSTALLPROPERTY_PRODUCTSTATE,value,&size);
    if(product_state_result(status,value)==LOOKUP_FAILED)operation_status(L"EXACT_UNMANAGED_PRODUCT",status);
    return product_state_result(status,value);
}
static int other_contexts_absent(Operation *o,const wchar_t *code) {
    wchar_t value[CAP]; DWORD size=CAP; UINT status;
    status=MsiGetProductInfoExW(code,NULL,MSIINSTALLCONTEXT_MACHINE,INSTALLPROPERTY_PRODUCTSTATE,value,&size);
    if(status!=ERROR_UNKNOWN_PRODUCT) { operation_status(L"EXACT_MACHINE_PRODUCT",status);return 0; }
    size=CAP;status=MsiGetProductInfoExW(code,o->sid,MSIINSTALLCONTEXT_USERMANAGED,INSTALLPROPERTY_PRODUCTSTATE,value,&size);
    if(status!=ERROR_UNKNOWN_PRODUCT)operation_status(L"EXACT_MANAGED_PRODUCT",status);
    return status==ERROR_UNKNOWN_PRODUCT;
}
static int reg_value(Operation *o,const wchar_t *key,const wchar_t *name,wchar_t *value) {
    HKEY handle=NULL; DWORD bytes=CAP*sizeof(wchar_t),type=0; LSTATUS status;
    status=RegOpenKeyExW(o->user,key,0,KEY_QUERY_VALUE|KEY_WOW64_64KEY,&handle);
    if (status==ERROR_FILE_NOT_FOUND) return ABSENT;
    if (status!=ERROR_SUCCESS) return LOOKUP_FAILED;
    status=RegQueryValueExW(handle,name,NULL,&type,(BYTE *)value,&bytes); RegCloseKey(handle);
    if (status==ERROR_FILE_NOT_FOUND) return ABSENT;
    if (status!=ERROR_SUCCESS || type!=REG_SZ || bytes<2 || bytes>CAP*2 || bytes%2 || value[bytes/2-1] || (wcslen(value)+1)*2!=bytes) return LOOKUP_FAILED;
    return PRESENT;
}
static int summary(MSIHANDLE db,UINT property_id,wchar_t *value) {
    MSIHANDLE h=0; UINT type=0; INT number=0; DWORD size=CAP; int ok=0;
    guard_check(L"SUMMARY_OPEN",property_id);
    if (!MsiGetSummaryInformationW(db,NULL,0,&h)) {
        guard_check(L"SUMMARY_PROPERTY",property_id);
        if(!MsiSummaryInfoGetPropertyW(h,property_id,&type,&number,NULL,value,&size)) {
            guard_check(L"SUMMARY_TYPE",property_id);
            guard_integer(L"SUMMARY_VARIANT_TYPE",(int)type,property_id);
            if(type==VT_LPSTR)ok=1;
        }
    }
    if (h) MsiCloseHandle(h); return ok;
}
static int package_header(MSIHANDLE db,const wchar_t *product,const wchar_t *name,const wchar_t *upgrade,const wchar_t *ver) {
    wchar_t value[CAP];GuardHeaderTrace trace={0},*saved=guard_header_trace;int ok=0;guard_header_trace=&trace;
#define HEADER_STEP(label,expression) (header_phase(L##label),(expression))
    if (!HEADER_STEP("PRODUCT_CODE_READ",db_property(db,L"ProductCode",value)) || !HEADER_STEP("PRODUCT_CODE_MATCH",same(value,product)) ||
        !HEADER_STEP("PRODUCT_NAME_READ",db_property(db,L"ProductName",value)) || !HEADER_STEP("PRODUCT_NAME_MATCH",same(value,name)) ||
        !HEADER_STEP("UPGRADE_CODE_READ",db_property(db,L"UpgradeCode",value)) || !HEADER_STEP("UPGRADE_CODE_MATCH",same(value,upgrade)) ||
        !HEADER_STEP("VERSION_READ",db_property(db,L"ProductVersion",value)) || !HEADER_STEP("VERSION_MATCH",same(value,ver)) ||
        !HEADER_STEP("SUMMARY_TEMPLATE",summary(db,7,value)) || HEADER_STEP("SUMMARY_ARCHITECTURE",_wcsnicmp(value,L"x64;",4)) ||
        !HEADER_STEP("SUMMARY_PACKAGE",summary(db,9,value)) || !HEADER_STEP("SUMMARY_PACKAGE_GUID",guid(value)) ||
        !HEADER_STEP("COMPONENTS",components(db,1,L"",NULL,NULL,NULL,NULL)) || !HEADER_STEP("SEALED_TABLES",sealed_tables(db))) goto done;
    ok=1;
done:
    guard_header_trace=saved;if(!ok)header_failure_log(&trace);return ok;
#undef HEADER_STEP
}
static int overlap(const wchar_t *a,const wchar_t *b) {
    size_t n=wcslen(b); return !_wcsnicmp(a,b,n) && (!a[n] || a[n]==L'\\');
}
/* Resolve aliases using the deepest existing non-reparse ancestor, then append the
 * missing suffix. Every existing parent is opened read-only with sharing. */
static int physical(Operation *o,const wchar_t *path,int file_allowed,wchar_t *result) {
    wchar_t candidate[CAP],full[CAP],suffix[CAP]=L"",piece[CAP]; HANDLE handle; DWORD attrs,error,n; wchar_t *tail;
    if (!canonical(path,candidate)) return 0;
    for (;;) {
        attrs=GetFileAttributesW(candidate);
        if (attrs!=INVALID_FILE_ATTRIBUTES) break;
        error=GetLastError(); if (error!=ERROR_FILE_NOT_FOUND && error!=ERROR_PATH_NOT_FOUND) return 0;
        tail=wcsrchr(candidate,L'\\'); if (!tail || tail<candidate+2) return 0;
        if (StringCchPrintfW(piece,CAP,L"%s%s%s",tail+1,suffix[0]?L"\\":L"",suffix)!=S_OK || StringCchCopyW(suffix,CAP,piece)!=S_OK) return 0;
        if (tail==candidate+2) tail[1]=0; else *tail=0;
    }
    if (attrs&FILE_ATTRIBUTE_REPARSE_POINT || (!file_allowed && !(attrs&FILE_ATTRIBUTE_DIRECTORY))) return 0;
    StringCchCopyW(full,CAP,candidate); tail=wcsrchr(full,L'\\');
    if (tail>full+2) *tail=0; else full[3]=0;
    if (!safe_ancestors(&o->api,full)) return 0;
    handle=CreateFileW(candidate,FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,NULL,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS,NULL);
    if (handle==INVALID_HANDLE_VALUE) return 0;
    if(!(attrs&FILE_ATTRIBUTE_DIRECTORY)) { BY_HANDLE_FILE_INFORMATION information;
        if(!GetFileInformationByHandle(handle,&information)||information.nNumberOfLinks!=1) { CloseHandle(handle);return 0; } }
    n=GetFinalPathNameByHandleW(handle,full,CAP,FILE_NAME_NORMALIZED|VOLUME_NAME_DOS); CloseHandle(handle);
    if (!n || n>=CAP || wcsncmp(full,L"\\\\?\\",4) || StringCchCopyW(piece,CAP,full+4)!=S_OK) return 0;
    if (suffix[0] && (StringCchCatW(piece,CAP,piece[wcslen(piece)-1]==L'\\'?L"":L"\\")!=S_OK || StringCchCatW(piece,CAP,suffix)!=S_OK)) return 0;
    return canonical(piece,result);
}
static int version_compare(const wchar_t *a,const wchar_t *b) {
    unsigned long x[3],y[3];
    if (swscanf_s(a,L"%lu.%lu.%lu",&x[0],&x[1],&x[2])!=3 || swscanf_s(b,L"%lu.%lu.%lu",&y[0],&y[1],&y[2])!=3) return 0;
    if (x[0]!=y[0]) return x[0]>y[0]?1:-1;
    if (x[1]!=y[1]) return x[1]>y[1]?1:-1;
    return x[2]==y[2]?0:(x[2]>y[2]?1:-1);
}
static int version_records(Operation *o,const wchar_t *ver,const wchar_t *code,const wchar_t *root,int fresh) {
    wchar_t key[CAP],value[CAP],normalized[CAP]; int state;
    if (StringCchPrintfW(key,CAP,L"Software\\ChatChatBar\\%s\\%s",o->name,ver)!=S_OK) return 0;
    state=reg_value(o,key,L"ProductCode",value);
    if (state<0 || (state==PRESENT && (fresh || !same(value,code)))) return 0;
    state=reg_value(o,key,L"CcbInstallDirectoryV2",value);
    if (state<0 || (state==PRESENT && (fresh || !canonical(value,normalized) || !same(root,normalized)))) return 0;
    return 1;
}
static int cache_product(Operation *o,const wchar_t *code,wchar_t *root,wchar_t *ver,MSIHANDLE *cached) {
    wchar_t cache[CAP],value[CAP],package[CAP],native_package[CAP]; DWORD attrs;
    *cached=0;
    if (!info(o,code,INSTALLPROPERTY_VERSIONSTRING,ver) || !version(ver) || !info(o,code,INSTALLPROPERTY_PRODUCTNAME,value) || !same(value,o->name) ||
        !info(o,code,INSTALLPROPERTY_INSTALLLOCATION,value) || !canonical(value,root) ||
        !info(o,code,INSTALLPROPERTY_LOCALPACKAGE,cache) || !physical(o,cache,1,value)) return 0;
    attrs=GetFileAttributesW(cache);
    if (attrs==INVALID_FILE_ATTRIBUTES || attrs&FILE_ATTRIBUTE_DIRECTORY || !wcsrchr(cache,L'.') || !same(wcsrchr(cache,L'.'),L".msi") ||
        MsiOpenDatabaseW(cache,MSIDBOPEN_READONLY,cached)!=ERROR_SUCCESS) return 0;
    if (!package_header(*cached,code,o->name,o->upgrade,ver) || !info(o,code,INSTALLPROPERTY_PACKAGECODE,native_package) ||
        !summary(*cached,9,package) || !same(native_package,package) || !version_records(o,ver,code,root,0)) return 0;
    return 1;
}
static int family(Operation *o,MSIHANDLE *old_db) {
    DWORD index; wchar_t code[39],hint[CAP],downgrade[CAP],ver[CAP],root[CAP]; UINT status; int seen_self=0,count=0;
    o->old[0]=0; *old_db=0;
    if (!property(&o->api,L"JP_UPGRADABLE_FOUND",hint) || !property(&o->api,L"JP_DOWNGRADABLE_FOUND",downgrade)) return 0;
    for (index=0;index<32;index++) {
        status=MsiEnumRelatedProductsW(o->upgrade,0,index,code);
        if (status==ERROR_NO_MORE_ITEMS) break;
        if (status!=ERROR_SUCCESS || !guid(code) || product_present(o,code)!=PRESENT || !other_contexts_absent(o,code)) return 0;
        if (same(code,o->product)) { if (!o->self_present || seen_self++) return 0; continue; }
        if (++count>1 || o->self_present || !cache_product(o,code,root,ver,old_db) || version_compare(o->ver,ver)<=0 ||
            StringCchCopyW(o->old,CAP,code)!=S_OK || StringCchCopyW(o->root,CAP,root)!=S_OK || StringCchCopyW(o->old_ver,CAP,ver)!=S_OK) return 0;
    }
    if (index==32 || downgrade[0] || (o->self_present && !seen_self)) return 0;
    if (count) return same(hint,o->old);
    return !hint[0];
}
static int no_unknown_version_owners(Operation *o) {
    wchar_t key[CAP],sub[CAP],value[CAP],path[CAP]; HKEY handle=NULL; DWORD index,size; LSTATUS status; int ok=0,state;
    if (StringCchPrintfW(key,CAP,L"Software\\ChatChatBar\\%s",o->name)!=S_OK) return 0;
    status=RegOpenKeyExW(o->user,key,0,KEY_ENUMERATE_SUB_KEYS|KEY_WOW64_64KEY,&handle);
    if (status==ERROR_FILE_NOT_FOUND) return 1;
    if (status!=ERROR_SUCCESS) return 0;
    for (index=0;index<128;index++) {
        size=CAP; status=RegEnumKeyExW(handle,index,sub,&size,NULL,NULL,NULL,NULL);
        if (status==ERROR_NO_MORE_ITEMS) { ok=1; break; }
        if (status!=ERROR_SUCCESS) break;
        if (same(sub,L"Components")) continue;
        if (!version(sub) || StringCchPrintfW(path,CAP,L"%s\\%s",key,sub)!=S_OK)break;
        state=reg_value(o,path,L"ProductCode",value);if(state<0)break;
        if(o->self_present&&same(sub,o->ver)) { if(state==PRESENT&&!same(value,o->product))break; }
        else if(o->old[0]&&same(sub,o->old_ver)) { if(state==PRESENT&&!same(value,o->old))break; }
        else break;
    }
    RegCloseKey(handle); return ok;
}

/* Bounded JSON reader for the locator only. Unknown JSON members are skipped, never
 * default a present invalid/unreadable locator. No user entity/credential reads. */
typedef struct { const wchar_t *p; unsigned depth; } JsonCursor;
static void json_space(JsonCursor *j) { while (*j->p==L' ' || *j->p==L'\n' || *j->p==L'\r' || *j->p==L'\t') j->p++; }
static int json_string(JsonCursor *j,wchar_t *out,DWORD cap) {
    DWORD n=0; wchar_t c; json_space(j); if (*j->p++!=L'"') return 0;
    while ((c=*j->p++)!=L'"') {
        if (!c || c<32) return 0;
        if (c==L'\\') {
            c=*j->p++;
            if (c==L'u') { unsigned v=0,i; for(i=0;i<4;i++) { wchar_t h=*j->p++; if(!iswxdigit(h)) return 0; v=v*16+(h<=L'9'?h-L'0':towupper(h)-L'A'+10); } c=(wchar_t)v; if(!c) return 0; }
            else if(c==L'n') c=L'\n'; else if(c==L'r') c=L'\r'; else if(c==L't') c=L'\t'; else if(c==L'b') c=L'\b'; else if(c==L'f') c=L'\f';
            else if(c!=L'"' && c!=L'\\' && c!=L'/') return 0;
        }
        if (out) { if(n+1>=cap) return 0; out[n]=c; } n++;
    }
    if(out) out[n]=0; return 1;
}
static int json_skip(JsonCursor *j) {
    wchar_t end; const wchar_t *start; json_space(j);
    if (*j->p==L'"') return json_string(j,NULL,0);
    if (*j->p==L'{' || *j->p==L'[') {
        end=*j->p++==L'{'?L'}':L']'; if(++j->depth>16) return 0; json_space(j);
        if(*j->p==end) { j->p++; j->depth--; return 1; }
        for(;;) { if(end==L'}' && (!json_string(j,NULL,0) || (json_space(j),*j->p++!=L':'))) return 0;
            if(!json_skip(j)) return 0; json_space(j); if(*j->p==end) { j->p++; j->depth--; return 1; }
            if(*j->p++!=L',') return 0;
        }
    }
    start=j->p; if(!wcsncmp(start,L"true",4)) j->p+=4; else if(!wcsncmp(start,L"false",5)) j->p+=5; else if(!wcsncmp(start,L"null",4)) j->p+=4;
    else { if(*j->p==L'-') j->p++; if(*j->p==L'0') j->p++; else { if(*j->p<L'1'||*j->p>L'9') return 0; while(iswdigit(*j->p)) j->p++; }
        if(*j->p==L'.') { j->p++; if(!iswdigit(*j->p)) return 0; while(iswdigit(*j->p))j->p++; }
        if(*j->p==L'e'||*j->p==L'E') { j->p++; if(*j->p==L'+'||*j->p==L'-')j->p++; if(!iswdigit(*j->p))return 0; while(iswdigit(*j->p))j->p++; }
    }
    return j->p>start;
}
static int locator_parse(const wchar_t *text,wchar_t *custom) {
    JsonCursor j={text,0}; wchar_t key[CAP],mode[CAP]=L"",number[CAP]; int format=0,seen_format=0,seen_mode=0,seen_custom=0;
    custom[0]=0; json_space(&j); if(*j.p++!=L'{')return 0; json_space(&j);
    while(*j.p!=L'}') {
        if(!json_string(&j,key,CAP))return 0; json_space(&j); if(*j.p++!=L':')return 0; json_space(&j);
        if(!wcscmp(key,L"formatVersion")) {
            if(seen_format++)return 0;
            if(*j.p==L'"') { if(!json_string(&j,number,CAP))return 0; format=!wcscmp(number,L"1"); }
            else { format=*j.p==L'1' && !iswdigit(j.p[1]) && j.p[1]!=L'.' && j.p[1]!=L'e' && j.p[1]!=L'E'; if(!json_skip(&j))return 0; }
        } else if(!wcscmp(key,L"mode")) { if(seen_mode++ || !json_string(&j,mode,CAP))return 0; }
        else if(!wcscmp(key,L"customRoot")) { if(seen_custom++ || !json_string(&j,custom,CAP))return 0; }
        else if(!json_skip(&j))return 0;
        json_space(&j); if(*j.p==L'}')break; if(*j.p++!=L',')return 0; json_space(&j); if(*j.p==L'}')return 0;
    }
    j.p++; json_space(&j); if(*j.p || !format || !seen_mode)return 0;
    return (!wcscmp(mode,L"DEFAULT") && !seen_custom) || (!wcscmp(mode,L"CUSTOM") && seen_custom && custom[0]);
}
static int data_isolated(Operation *o) {
    wchar_t path[CAP],program[CAP],data[CAP],custom[CAP],normalized[CAP]; HANDLE file=INVALID_HANDLE_VALUE;
    DWORD attrs,error,length,read; BYTE *bytes=NULL; wchar_t *text=NULL; int ok=0,n;
    if (!physical(o,o->root,0,program) || same(program,o->local) || overlap(program,o->windows) || overlap(o->windows,program) ||
        StringCchPrintfW(path,CAP,L"%s\\ChatChatBarDesktop",o->local)!=S_OK || !physical(o,path,0,data) || overlap(program,data) || overlap(data,program)) return 0;
    if(StringCchPrintfW(path,CAP,L"%s\\ChatChatBarDesktopCredentials",o->local)!=S_OK || !physical(o,path,0,data) || overlap(program,data) || overlap(data,program)) return 0;
    if(StringCchPrintfW(path,CAP,L"%s\\ChatChatBarDesktop.bootstrap.json",o->local)!=S_OK) return 0;
    if(overlap(program,path)||overlap(path,program))return 0;
    attrs=GetFileAttributesW(path);
    if(attrs==INVALID_FILE_ATTRIBUTES) { error=GetLastError(); return error==ERROR_FILE_NOT_FOUND || error==ERROR_PATH_NOT_FOUND; }
    if(attrs&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT) || !physical(o,path,1,normalized))return 0;
    file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
    if(file==INVALID_HANDLE_VALUE)goto done;
    length=GetFileSize(file,NULL); if(!length || length>65536)goto done;
    bytes=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,length+1); text=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,(length+1)*sizeof(wchar_t));
    if(!bytes || !text || !ReadFile(file,bytes,length,&read,NULL) || read!=length || memchr(bytes,0,length))goto done;
    n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,(char *)bytes,(int)length,text,(int)length); if(n<=0)goto done;
    text[n]=0; if(!locator_parse(text,custom))goto done;
    if(custom[0] && (!canonical(custom,normalized) || !physical(o,normalized,0,data) || overlap(program,data) || overlap(data,program)))goto done;
    ok=1;
done:
    if(file!=INVALID_HANDLE_VALUE)CloseHandle(file); if(bytes)HeapFree(GetProcessHeap(),0,bytes); if(text)HeapFree(GetProcessHeap(),0,text); return ok;
}

typedef struct { wchar_t id[CAP],parent[CAP],name[CAP],relative[CAP]; int state; } OpDirectory;
static int target_segment(const wchar_t *input,wchar_t *output) {
    wchar_t value[CAP],dummy[CAP],*p,*bar;
    if(StringCchCopyW(value,CAP,input)!=S_OK)return 0;
    p=wcschr(value,L':'); if(p)*p=0; bar=wcschr(value,L'|'); if(bar)bar++; else bar=value;
    if(!*bar || StringCchPrintfW(dummy,CAP,L"C:\\Root\\%s",bar)!=S_OK || !canonical(dummy,output))return 0;
    return StringCchCopyW(output,CAP,bar)==S_OK;
}
static int directory_relative(OpDirectory *d,DWORD count,DWORD index,DWORD depth) {
    DWORD p; wchar_t name[CAP];
    if(depth>64 || d[index].state<0)return 0; if(d[index].state==1)return 1; d[index].state=-1;
    if(same(d[index].id,L"INSTALLDIR")) { d[index].relative[0]=0; d[index].state=1; return 1; }
    for(p=0;p<count;p++)if(!wcscmp(d[p].id,d[index].parent))break;
    if(p==count || !directory_relative(d,count,p,depth+1) || !target_segment(d[index].name,name) ||
        StringCchPrintfW(d[index].relative,CAP,L"%s%s%s",d[p].relative,d[p].relative[0]?L"\\":L"",name)!=S_OK)return 0;
    d[index].state=1;return 1;
}
static int target_path(MSIHANDLE install,const wchar_t *directory,wchar_t *out) {
    DWORD size=CAP; wchar_t value[CAP]; return MsiGetTargetPathW(install,directory,value,&size)==ERROR_SUCCESS && canonical(value,out);
}
static int formatted(MSIHANDLE install,const wchar_t *input,wchar_t *out) {
    MSIHANDLE r=MsiCreateRecord(0); DWORD size=CAP; int ok=0;
    if(r && !MsiRecordSetStringW(r,0,input) && !MsiFormatRecordW(install,r,out,&size))ok=1;
    if(r)MsiCloseHandle(r);return ok;
}
static int runtime_targets(Operation *o) {
    OpDirectory *dirs=NULL; DWORD count=0,i; MSIHANDLE view=0,row=0; UINT status; int ok=0;
    wchar_t expected[CAP],actual[CAP],identity[CAP],id[CAP],component[CAP],filename[CAP],query[CAP],dir[CAP]; MSIHANDLE install=(MSIHANDLE)(UINT_PTR)o->api.context;
    dirs=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,1024*sizeof(*dirs));if(!dirs)goto done;
    if(MsiDatabaseOpenViewW(o->db,L"SELECT * FROM `Directory`",&view)||MsiViewExecute(view,0))goto done;
    while((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        if(count>=1024||!field(row,1,dirs[count].id)||!field(row,2,dirs[count].parent)||!field(row,3,dirs[count].name))goto done;
        if(costing_directory_row(row)) { MsiCloseHandle(row);row=0;continue; }
        count++;MsiCloseHandle(row);row=0;
    }
    if(status!=ERROR_NO_MORE_ITEMS)goto done;MsiCloseHandle(view);view=0;
    for(i=0;i<count;i++) {
        if(same(dirs[i].id,L"TARGETDIR")||same(dirs[i].id,L"LocalAppDataFolder")||same(dirs[i].id,L"DesktopFolder")||same(dirs[i].id,L"ProgramMenuFolder"))continue;
        if(!directory_relative(dirs,count,i,0)||StringCchPrintfW(expected,CAP,L"%s%s%s",o->root,dirs[i].relative[0]?L"\\":L"",dirs[i].relative)!=S_OK||
            !target_path(install,dirs[i].id,actual)||!same(expected,actual)||!physical(o,actual,0,identity))goto done;
    }
    if(MsiDatabaseOpenViewW(o->db,L"SELECT `File`,`Component_`,`FileName` FROM `File`",&view)||MsiViewExecute(view,0))goto done;
    while((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        MSIHANDLE v=0,r=0; int found=0;
        if(!field(row,1,id)||!field(row,2,component)||!field(row,3,filename)||wcschr(component,L'\'')||
            StringCchPrintfW(query,CAP,L"SELECT `Directory_` FROM `Component` WHERE `Component`='%s'",component)!=S_OK)goto done;
        if(!MsiDatabaseOpenViewW(o->db,query,&v)&&!MsiViewExecute(v,0)&&!MsiViewFetch(v,&r)&&field(r,1,dir))found=1;
        if(r)MsiCloseHandle(r);if(v)MsiCloseHandle(v);if(!found)goto done;
        for(i=0;i<count;i++)if(!wcscmp(dir,dirs[i].id))break;
        if(i==count||!target_segment(filename,dir)||StringCchPrintfW(expected,CAP,L"%s%s%s\\%s",o->root,dirs[i].relative[0]?L"\\":L"",dirs[i].relative,dir)!=S_OK)goto done;
        /* root files have no extra separator before their name */
        if(!dirs[i].relative[0]&&StringCchPrintfW(expected,CAP,L"%s\\%s",o->root,dir)!=S_OK)goto done;
        if(StringCchPrintfW(query,CAP,L"[#%s]",id)!=S_OK||!formatted(install,query,actual)||!canonical(actual,dir)||!same(dir,expected)||!physical(o,dir,1,identity))goto done;
        MsiCloseHandle(row);row=0;
    }
    if(status!=ERROR_NO_MORE_ITEMS)goto done;
    /* Shortcut support remains fail-closed until the production profile has a pinned
     * known-folder/link ownership adapter. Synthetic packages contain no links. */
    MsiCloseHandle(view);view=0;
    if(MsiDatabaseIsTablePersistentW(o->db,L"Shortcut")==MSICONDITION_TRUE) {
        if(MsiDatabaseOpenViewW(o->db,L"SELECT `Shortcut` FROM `Shortcut`",&view)||MsiViewExecute(view,0)||MsiViewFetch(view,&row)!=ERROR_NO_MORE_ITEMS)goto done;
    }
    ok=1;
done:
    if(row)MsiCloseHandle(row);if(view)MsiCloseHandle(view);if(dirs)HeapFree(GetProcessHeap(),0,dirs);return ok;
}
static int full_plan(Operation *o) {
    MSIHANDLE view=0,row=0; UINT status; int ok=0; wchar_t id[CAP],remove[CAP],upgrading[CAP],code[CAP]; INSTALLSTATE installed,action;
    if(!property(&o->api,L"REMOVE",remove))return 0;
    if(remove[0]&&!same(remove,L"ALL"))return 0;
    o->operation=remove[0]?(o->self_present?L'D':L'?'):(o->self_present?L'R':(o->old[0]?L'U':L'F'));
    if(o->operation==L'?')return 0;
    if(!property(&o->api,L"UPGRADINGPRODUCTCODE",upgrading))return 0;
    if(upgrading[0]) { if(o->operation!=L'D'||!guid(upgrading)||same(upgrading,o->product))return 0; o->operation=L'C'; }
    if(MsiDatabaseOpenViewW(o->db,L"SELECT `Feature` FROM `Feature`",&view)||MsiViewExecute(view,0))goto done;
    while((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        if(!field(row,1,id)||MsiGetFeatureStateW((MSIHANDLE)(UINT_PTR)o->api.context,id,&installed,&action)!=ERROR_SUCCESS||
            ((o->operation==L'D'||o->operation==L'C')?action!=INSTALLSTATE_ABSENT:!(action==INSTALLSTATE_LOCAL||(action==INSTALLSTATE_UNKNOWN&&installed==INSTALLSTATE_LOCAL))))goto done;
        MsiCloseHandle(row);row=0;
    }
    if(status!=ERROR_NO_MORE_ITEMS)goto done;MsiCloseHandle(view);view=0;
    if(MsiDatabaseOpenViewW(o->db,L"SELECT `Component`,`ComponentId` FROM `Component`",&view)||MsiViewExecute(view,0))goto done;
    while((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        if(field(row,1,id)&&costing_component_id(o->db,id)) { MsiCloseHandle(row);row=0;continue; }
        if(!field(row,1,id)||!field(row,2,code)||MsiGetComponentStateW((MSIHANDLE)(UINT_PTR)o->api.context,id,&installed,&action)!=ERROR_SUCCESS||
            !(action==INSTALLSTATE_LOCAL||action==INSTALLSTATE_ABSENT||action==INSTALLSTATE_UNKNOWN))goto done;
        if(o->operation!=L'D'&&o->operation!=L'C'&&!(action==INSTALLSTATE_LOCAL||(action==INSTALLSTATE_UNKNOWN&&installed==INSTALLSTATE_LOCAL)))goto done;
        if(o->self_present) { INSTALLSTATE registered;
            if(MsiQueryComponentStateW(o->product,o->sid,MSIINSTALLCONTEXT_USERUNMANAGED,code,&registered)!=ERROR_SUCCESS||registered!=INSTALLSTATE_LOCAL)goto done; }
        MsiCloseHandle(row);row=0;
    }
    ok=status==ERROR_NO_MORE_ITEMS;
done:if(row)MsiCloseHandle(row);if(view)MsiCloseHandle(view);return ok;
}
static int exact_markers(Operation *o,MSIHANDLE old_db) {
    MSIHANDLE view=0,row=0; UINT status; wchar_t key[CAP],name[CAP],raw[CAP],expected[CAP],actual[CAP]; int state,ok=0;
    if(MsiDatabaseOpenViewW(o->db,L"SELECT `Key`,`Name`,`Value` FROM `Registry`",&view)||MsiViewExecute(view,0))goto done;
    while((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        if(!field(row,1,key)||!field(row,2,name)||!field(row,3,raw)||!formatted((MSIHANDLE)(UINT_PTR)o->api.context,raw,expected))goto done;
        state=reg_value(o,key,name,actual);
        if(state<0)goto done;
        if(state==PRESENT) {
            if(!o->self_present&&!o->old[0])goto done;
            if(!o->self_present&&o->old[0]) {
                MSIHANDLE v=0,r=0;wchar_t query[CAP],prior[CAP];int owned=0;
                if(wcschr(key,L'\'')||wcschr(name,L'\'')||StringCchPrintfW(query,CAP,L"SELECT `Value` FROM `Registry` WHERE `Key`='%s' AND `Name`='%s'",key,name)!=S_OK)goto done;
                if(old_db&&!MsiDatabaseOpenViewW(old_db,query,&v)&&!MsiViewExecute(v,0)&&!MsiViewFetch(v,&r)&&field(r,1,prior)&&same(prior,raw))owned=1;
                if(r)MsiCloseHandle(r);if(v)MsiCloseHandle(v);if(!owned)goto done;
            }
            if(same(raw,L"[INSTALLDIR]")) { wchar_t value[CAP];if(!canonical(expected,value)||!canonical(actual,raw)||!same(value,raw))goto done; }
            else if(!same(expected,actual))goto done;
        }
        MsiCloseHandle(row);row=0;
    }
    ok=status==ERROR_NO_MORE_ITEMS;
done:if(row)MsiCloseHandle(row);if(view)MsiCloseHandle(view);return ok;
}
static int equal_query(MSIHANDLE a,MSIHANDLE b,const wchar_t *query,DWORD width) {
    MSIHANDLE av=0,bv=0,ar=0,br=0;UINT as,bs;DWORD i,count=0;wchar_t x[CAP],y[CAP];int ok=0;
    if(MsiDatabaseOpenViewW(a,query,&av)||MsiViewExecute(av,0)||MsiDatabaseOpenViewW(b,query,&bv)||MsiViewExecute(bv,0))goto done;
    for(;;) {
        as=MsiViewFetch(av,&ar);bs=MsiViewFetch(bv,&br);
        if(as==ERROR_NO_MORE_ITEMS&&bs==ERROR_NO_MORE_ITEMS) { ok=1;break; }
        if(as||bs||++count>2048)break;
        for(i=1;i<=width;i++)if(!field(ar,i,x)||!field(br,i,y)||wcscmp(x,y))goto done;
        MsiCloseHandle(ar);ar=0;MsiCloseHandle(br);br=0;
    }
done:if(ar)MsiCloseHandle(ar);if(br)MsiCloseHandle(br);if(av)MsiCloseHandle(av);if(bv)MsiCloseHandle(bv);return ok;
}
static int equal_directory_chain(MSIHANDLE a,MSIHANDLE b,const wchar_t *id,DWORD depth) {
    MSIHANDLE view=0,row=0;wchar_t query[CAP],parent[CAP];int ok=0;
    if(same(id,L"INSTALLDIR"))return 1;
    if(depth>64||wcschr(id,L'\'')||StringCchPrintfW(query,CAP,L"SELECT `Directory_Parent`,`DefaultDir` FROM `Directory` WHERE `Directory`='%s'",id)!=S_OK||
        !equal_query(a,b,query,2)||MsiDatabaseOpenViewW(a,query,&view)||MsiViewExecute(view,0)||MsiViewFetch(view,&row)||!field(row,1,parent))goto done;
    ok=equal_directory_chain(a,b,parent,depth+1);
done:if(row)MsiCloseHandle(row);if(view)MsiCloseHandle(view);return ok;
}
static int compatible_shared_layout(MSIHANDLE active,MSIHANDLE old) {
    /* A common GUID may update payload, but may not change registry KeyPath or its
     * declared directory/file identities. Nonshared components use normal MSI rules. */
    MSIHANDLE view=0,row=0,v=0,r=0; wchar_t code[CAP],id[CAP],dir[CAP],key[CAP],query[CAP],other[CAP]; UINT status; int ok=0;
    if(!old)return 1;
    if(MsiDatabaseOpenViewW(active,L"SELECT `Component`,`ComponentId`,`Directory_`,`KeyPath` FROM `Component`",&view)||MsiViewExecute(view,0))goto done;
    while((status=MsiViewFetch(view,&row))==ERROR_SUCCESS) {
        if(!field(row,1,id)||!field(row,2,code)||!field(row,3,dir)||!field(row,4,key)||
            StringCchPrintfW(query,CAP,L"SELECT `Component`,`Directory_`,`KeyPath` FROM `Component` WHERE `ComponentId`='%s'",code)!=S_OK||
            MsiDatabaseOpenViewW(old,query,&v)||MsiViewExecute(v,0))goto done;
        status=MsiViewFetch(v,&r);
        if(status==ERROR_SUCCESS) {
            wchar_t left[5][CAP],right[5][CAP];DWORD j;
            if(!field(r,1,other)||!same(id,other)||!field(r,2,other)||!same(dir,other)||!field(r,3,other)||
                !registry_row(active,key,left)||!registry_row(old,other,right))goto done;
            for(j=0;j<5;j++)if(wcscmp(left[j],right[j]))goto done;
            if(!equal_directory_chain(active,old,dir,0)||wcschr(id,L'\''))goto done;
            if(StringCchPrintfW(query,CAP,L"SELECT `File`,`FileName` FROM `File` WHERE `Component_`='%s' ORDER BY `File`",id)!=S_OK||!equal_query(active,old,query,2))goto done;
            if(StringCchPrintfW(query,CAP,L"SELECT `Root`,`Key`,`Name`,`Value` FROM `Registry` WHERE `Component_`='%s' ORDER BY `Registry`",id)!=S_OK||!equal_query(active,old,query,4))goto done;
            if(StringCchPrintfW(query,CAP,L"SELECT `FileName`,`DirProperty`,`InstallMode` FROM `RemoveFile` WHERE `Component_`='%s' ORDER BY `FileKey`",id)!=S_OK||!equal_query(active,old,query,3))goto done;
        } else if(status!=ERROR_NO_MORE_ITEMS)goto done;
        if(r)MsiCloseHandle(r);r=0;MsiCloseHandle(v);v=0;MsiCloseHandle(row);row=0;
    }
    ok=status==ERROR_NO_MORE_ITEMS;
done:if(r)MsiCloseHandle(r);if(v)MsiCloseHandle(v);if(row)MsiCloseHandle(row);if(view)MsiCloseHandle(view);return ok;
}
static UINT operation_run(MSIHANDLE install,int final) {
    Operation o={0}; MSIHANDLE cached=0,old_db=0; wchar_t requested[CAP],value[CAP],normalized[CAP],ver[CAP],installed[CAP];
    const wchar_t *failure=L"CONTEXT_OR_HEADER"; UINT result=ERROR_INSTALL_FAILURE; int state;
    o.api.context=(void *)(UINT_PTR)install;o.api.get=real_get;o.api.set=real_set;o.api.attrs=real_attrs;o.api.empty=real_empty;o.api.log=real_log;
    operation_diagnostic_install=install;
    o.db=MsiGetActiveDatabase(install);
    failure=L"ACTIVE_DATABASE";if(!o.db)goto done;
    failure=L"EFFECTIVE_USER_CONTEXT";if(!context_open(&o))goto done;
    failure=L"AUTHORED_PRODUCT_HEADER";
    if(!db_property(o.db,L"ProductCode",o.product)||!guid(o.product)||!db_property(o.db,L"ProductName",o.name)||
        !db_property(o.db,L"UpgradeCode",o.upgrade)||!db_property(o.db,L"ProductVersion",o.ver)||!version(o.ver))goto done;
    if(!((same(o.name,L"ChatChatBarDesktop")&&same(o.upgrade,L"{8E4FDDBD-141F-5C1D-BFB2-1B48A6E46B81}"))||
        (same(o.name,L"ChatChatBarDesktopSynthetic")&&same(o.upgrade,L"{58E95FD1-709D-5813-9B5B-F518A4612BAE}"))))goto done;
    failure=L"ACTIVE_RESOURCE_CONTRACT";if(!package_header(o.db,o.product,o.name,o.upgrade,o.ver))goto done;
    { const wchar_t *names[]={L"ProductCode",L"ProductName",L"ProductVersion",L"UpgradeCode"};const wchar_t *values[]={o.product,o.name,o.ver,o.upgrade};DWORD i;
      for(i=0;i<ARRAYSIZE(names);i++)if(!property(&o.api,names[i],value)||!same(value,values[i]))goto done; }
    failure=L"UNSUPPORTED_SESSION";
    { static const wchar_t *blocked[]={L"ALLUSERS",L"TRANSFORMS",L"PATCH",L"MSINEWINSTANCE",L"ADDSOURCE",L"ADVERTISE",L"DISABLEROLLBACK",L"RollbackDisabled"};DWORD i;
      for(i=0;i<ARRAYSIZE(blocked);i++)if(!property(&o.api,blocked[i],value)||value[0])goto done; }
    if(!property(&o.api,L"MSIINSTALLPERUSER",value)||(value[0]&&!same(value,L"1")))goto done;
    failure=L"EXACT_PRODUCT_OR_FAMILY";
    state=product_present(&o,o.product);if(state<0||!other_contexts_absent(&o,o.product))goto done;o.self_present=state==PRESENT;
    if(!property(&o.api,L"Installed",installed)||(!!installed[0]!=o.self_present)||!family(&o,&old_db)||!property(&o.api,L"INSTALLDIR",requested))goto done;
    if(o.self_present) {
        if(!cache_product(&o,o.product,o.root,ver,&cached)||!same(ver,o.ver)||!summary(cached,9,value)||!summary(o.db,9,normalized)||!same(value,normalized))goto done;
    }
    if(!o.self_present&&!o.old[0]) {
        if(requested[0]) { if(!canonical(requested,o.root))goto done; }
        else if(StringCchPrintfW(o.root,CAP,L"%s\\%sApp",o.local,o.name)!=S_OK)goto done;
    }
    failure=L"DIRECTORY_OWNERSHIP";
    if(requested[0]&&(!canonical(requested,normalized)||!same(normalized,o.root)))goto done;
    if(!physical(&o,o.root,0,normalized)||!no_unknown_version_owners(&o)||!version_records(&o,o.ver,o.product,o.root,!o.self_present)||
        (!o.self_present&&!o.old[0]&&real_empty(NULL,o.root)!=PRESENT)||!compatible_shared_layout(o.db,old_db))goto done;
    if(!final) {
        if(real_set(o.api.context,L"INSTALLDIR",o.root)!=PRESENT)goto done;
        real_log(o.api.context,L"CCB_OPERATION_ROOT_RESOLVED");result=ERROR_SUCCESS;goto done;
    }
    failure=L"ROLLBACK_OR_FULL_PLAN";
    if(!MsiGetMode(install,MSIRUNMODE_ROLLBACKENABLED)||!full_plan(&o))goto done;
    failure=L"FINAL_TARGET_OR_MARKER";
    if(!runtime_targets(&o)||!exact_markers(&o,old_db))goto done;
    failure=L"DATA_ROOT_ISOLATION";
    if(o.operation!=L'D'&&o.operation!=L'C'&&!data_isolated(&o))goto done;
    StringCchPrintfW(value,CAP,L"CCB_OPERATION_ADMITTED operation=%c contract=1 inventory=NOT_REQUIRED rollback=ENABLED",o.operation);
    real_log(o.api.context,value);result=ERROR_SUCCESS;
done:
    if(result!=ERROR_SUCCESS) { StringCchPrintfW(value,CAP,L"CCB_OPERATION_DENIED stage=%s",failure);real_log(o.api.context,value); }
    if(cached)MsiCloseHandle(cached);if(old_db)MsiCloseHandle(old_db);if(o.db)MsiCloseHandle(o.db);
    if(o.user)RegCloseKey(o.user);if(o.sid)LocalFree(o.sid);if(o.token)CloseHandle(o.token);operation_diagnostic_install=0;return result;
}
