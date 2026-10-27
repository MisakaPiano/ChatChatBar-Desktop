/* Dedicated test-package entry. Never compiled into the normal guard. A successful
 * Header is a diagnostic result, NEVER permission to continue installation. */
typedef struct {
    MSIHANDLE (__stdcall *database)(MSIHANDLE);
    int (*context)(Operation *);
    void (__stdcall *log)(void *,const wchar_t *);
    void (*close_database)(MSIHANDLE);
    int real_session;
} HeaderProbeApi;
static void probe_close_database(MSIHANDLE db) { MsiCloseHandle(db); }
static UINT header_probe_run(MSIHANDLE install,const HeaderProbeApi *api) {
    Operation o={0};wchar_t marker[CAP];const wchar_t *failure=L"ACTIVE_DATABASE";
    MSIHANDLE saved_install=operation_diagnostic_install;
    o.api.context=(void *)(UINT_PTR)install;o.api.get=real_get;o.api.log=api->log;
    operation_diagnostic_install=api->real_session?install:0;
    api->log(o.api.context,L"CCB_HEADER_PROBE_BEGIN contract=1");
    o.db=api->database(install);if(!o.db)goto denied;
    failure=L"EFFECTIVE_USER_CONTEXT";if(!api->context(&o))goto denied;
    failure=L"AUTHORED_PRODUCT_HEADER";
    if(!db_property(o.db,L"ProductCode",o.product)||!guid(o.product)||
       !db_property(o.db,L"ProductName",o.name)||!db_property(o.db,L"UpgradeCode",o.upgrade)||
       !db_property(o.db,L"ProductVersion",o.ver)||!version(o.ver))goto denied;
    /* Same accepted synthetic name/family as operation_run; no production identity. */
    if(!same(o.name,L"ChatChatBarDesktopSynthetic")||
       !same(o.upgrade,L"{58E95FD1-709D-5813-9B5B-F518A4612BAE}"))goto denied;
    failure=L"PROBE_MARKER";
    if(!db_property(o.db,L"CCB_HEADER_PROBE_ONLY",marker)||!same(marker,L"1"))goto denied;
    api->log(o.api.context,package_header(o.db,o.product,o.name,o.upgrade,o.ver)?
        L"CCB_HEADER_PROBE_RESULT result=PASS":L"CCB_HEADER_PROBE_RESULT result=REJECT");
    goto done;
denied:
    StringCchPrintfW(marker,CAP,L"CCB_HEADER_PROBE_PRECONDITION stage=%s",failure);
    api->log(o.api.context,marker);
done:
    api->log(o.api.context,L"CCB_HEADER_PROBE_STOP result=1603");
    if(o.db)api->close_database(o.db);
    if(o.user)RegCloseKey(o.user);if(o.sid)LocalFree(o.sid);if(o.token)CloseHandle(o.token);
    operation_diagnostic_install=saved_install;
    return ERROR_INSTALL_FAILURE;
}
__declspec(dllexport) UINT __stdcall CcbHeaderDiagnosticOnly(MSIHANDLE install) {
    const HeaderProbeApi api={MsiGetActiveDatabase,context_open,real_log,probe_close_database,1};
    return header_probe_run(install,&api);
}
#ifdef CCB_GUARD_TEST
static __declspec(thread) MSIHANDLE probe_test_database;
static __declspec(thread) UINT probe_test_mode,probe_test_closes;
static MSIHANDLE __stdcall probe_fake_database(MSIHANDLE unused) {
    (void)unused;return probe_test_mode==1?0:probe_test_database;
}
static int probe_fake_context(Operation *o) { (void)o;return probe_test_mode!=2; }
static void __stdcall probe_fake_log(void *unused,const wchar_t *line) {
    (void)unused;if(header_diagnostic_sink)header_diagnostic_sink(line);
}
static void probe_fake_close(MSIHANDLE unused) { (void)unused;probe_test_closes++; }
__declspec(dllexport) UINT __stdcall CcbTestHeaderProbe(MSIHANDLE db,UINT mode,HeaderDiagnosticSink sink) {
    const HeaderProbeApi api={probe_fake_database,probe_fake_context,probe_fake_log,probe_fake_close,0};
    HeaderDiagnosticSink saved=header_diagnostic_sink;UINT result;
    probe_test_database=db;probe_test_mode=mode;probe_test_closes=0;header_diagnostic_sink=sink;
    result=header_probe_run(0,&api);header_diagnostic_sink=saved;probe_test_database=0;
    return result;
}
__declspec(dllexport) UINT __stdcall CcbTestHeaderProbeCloses(void) { return probe_test_closes; }
#endif
