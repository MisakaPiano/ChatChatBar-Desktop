/* Immediate MSI directory admission. No file, registration or credential writes. */
#define UNICODE
#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <msi.h>
#include <msiquery.h>
#include <wchar.h>
#include <wctype.h>
#include <strsafe.h>

#define CAP 1024
#define ABSENT 0
#define PRESENT 1
#define FAILED (-1)

typedef int (__stdcall *Get)(void *, const wchar_t *, wchar_t *, DWORD);
typedef int (__stdcall *Set)(void *, const wchar_t *, const wchar_t *);
typedef int (__stdcall *Saved)(void *, const wchar_t *, const wchar_t *, const wchar_t *, wchar_t *, DWORD);
typedef int (__stdcall *Old)(void *, const wchar_t *, const wchar_t *, const wchar_t *, wchar_t *, DWORD);
typedef DWORD (__stdcall *Attrs)(void *, const wchar_t *, DWORD *);
typedef int (__stdcall *Empty)(void *, const wchar_t *);
typedef void (__stdcall *Log)(void *, const wchar_t *);
typedef struct { void *context; Get get; Set set; Saved saved; Old old; Attrs attrs; Empty empty; Log log; } Api;

static UINT refuse(Api *api, const wchar_t *code) {
    api->log(api->context, code);
    return ERROR_INSTALL_FAILURE;
}

static int same(const wchar_t *a, const wchar_t *b) { return _wcsicmp(a, b) == 0; }
static int property(Api *api, const wchar_t *name, wchar_t *value) {
    value[0] = 0;
    return api->get(api->context, name, value, CAP) >= 0;
}
static int guid(const wchar_t *s) {
    size_t i;
    if (wcslen(s) != 38 || s[0] != L'{' || s[37] != L'}') return 0;
    for (i = 1; i < 37; i++) {
        if (i == 9 || i == 14 || i == 19 || i == 24) { if (s[i] != L'-') return 0; }
        else if (!iswxdigit(s[i])) return 0;
    }
    return 1;
}
static int version(const wchar_t *s) {
    int dots = 0, digits = 0;
    if (!*s) return 0;
    while (*s) {
        if (*s == L'.') { if (!digits || ++dots > 2) return 0; digits = 0; }
        else if (*s >= L'0' && *s <= L'9') digits++;
        else return 0;
        s++;
    }
    return dots == 2 && digits > 0;
}

/* Conservative DOS path contract: no UNC/device/relative/dot/ADS/reserved segments. */
static int canonical(const wchar_t *input, wchar_t *output) {
    size_t n = wcslen(input), i, start;
    wchar_t segment[CAP], stem[CAP];
    if (n < 4 || n > 240 || !((input[0] >= L'A' && input[0] <= L'Z') || (input[0] >= L'a' && input[0] <= L'z')) || input[1] != L':' || input[2] != L'\\') return 0;
    if (StringCchCopyW(output, CAP, input) != S_OK) return 0;
    while (n > 3 && output[n - 1] == L'\\') output[--n] = 0;
    if (n <= 3) return 0;
    start = 3;
    for (i = 3; i <= n; i++) {
        wchar_t c = output[i];
        if (c && (c < 32 || wcschr(L"/:*?\"<>|%", c))) return 0;
        if (!c || c == L'\\') {
            size_t length = i - start, j;
            if (!length || length >= CAP || output[i - 1] == L'.' || output[i - 1] == L' ') return 0;
            if (StringCchCopyNW(segment, CAP, output + start, length) != S_OK) return 0;
            if (!wcscmp(segment, L".") || !wcscmp(segment, L"..")) return 0;
            StringCchCopyW(stem, CAP, segment);
            for (j = 0; stem[j] && stem[j] != L'.'; j++) { }
            stem[j] = 0;
            if (same(stem, L"CON") || same(stem, L"PRN") || same(stem, L"AUX") || same(stem, L"NUL")) return 0;
            if (wcslen(stem) == 4 && (!_wcsnicmp(stem, L"COM", 3) || !_wcsnicmp(stem, L"LPT", 3)) && stem[3] >= L'1' && stem[3] <= L'9') return 0;
            start = i + 1;
        }
    }
    output[0] = towupper(output[0]);
    return 1;
}

static int safe_ancestors(Api *api, const wchar_t *path) {
    wchar_t prefix[CAP]; size_t i, n = wcslen(path); DWORD error, attributes;
    StringCchCopyW(prefix, CAP, path);
    prefix[3] = 0;
    attributes = api->attrs(api->context, prefix, &error);
    if (attributes == INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_REPARSE_POINT) || !(attributes & FILE_ATTRIBUTE_DIRECTORY)) return 0;
    StringCchCopyW(prefix, CAP, path);
    for (i = 3; i <= n; i++) {
        if (i == n || prefix[i] == L'\\') {
            wchar_t c = prefix[i]; prefix[i] = 0;
            attributes = api->attrs(api->context, prefix, &error);
            prefix[i] = c;
            if (attributes == INVALID_FILE_ATTRIBUTES) {
                if (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND) return 1;
                return 0;
            }
            if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) || !(attributes & FILE_ATTRIBUTE_DIRECTORY)) return 0;
        }
    }
    return 1;
}

static UINT resolve(Api *api, int revalidate) {
    wchar_t name[CAP], upgrade[CAP], product[CAP], ver[CAP], installed[CAP], saved[CAP], related[CAP];
    wchar_t requested[CAP], selected[CAP], normalized[CAP], local[CAP], data[CAP], old[CAP], prior[CAP], windows[CAP];
    const wchar_t *expected; int status, fresh = 0;
    if (!property(api, L"ProductName", name) || !property(api, L"UpgradeCode", upgrade) ||
        !property(api, L"ProductCode", product) || !property(api, L"ProductVersion", ver) ||
        !property(api, L"Installed", installed) || !property(api, L"INSTALLDIR", requested) ||
        !property(api, L"LocalAppDataFolder", local) || !property(api, L"WindowsFolder", windows)) return refuse(api, L"CCB_DIRECTORY_PROPERTY_UNAVAILABLE");
    if (same(name, L"ChatChatBarDesktop")) expected = L"{8E4FDDBD-141F-5C1D-BFB2-1B48A6E46B81}";
    else if (same(name, L"ChatChatBarDesktopSynthetic")) expected = L"{58E95FD1-709D-5813-9B5B-F518A4612BAE}";
    else return refuse(api, L"CCB_DIRECTORY_PRODUCT_UNKNOWN");
    if (!same(upgrade, expected) || !guid(product) || !version(ver)) return refuse(api, L"CCB_DIRECTORY_IDENTITY_INVALID");
    if (revalidate) {
        if (!property(api, L"CcbResolvedDirectory", prior) || !*prior || !canonical(requested, selected) || !same(prior, selected))
            return refuse(api, L"CCB_DIRECTORY_CHANGED_AFTER_SELECTION");
    } else {
        if (api->set(api->context, L"CcbResolvedDirectory", L"") != PRESENT) return refuse(api, L"CCB_DIRECTORY_PROPERTY_UNAVAILABLE");
        status = api->saved(api->context, name, ver, product, saved, CAP);
        if (status == FAILED) return refuse(api, L"CCB_DIRECTORY_SAVED_RECORD_UNAVAILABLE");
        if (*installed) {
            if (status != PRESENT || !*saved) return refuse(api, L"CCB_DIRECTORY_SAVED_RECORD_MISSING");
            if (!canonical(saved, selected)) return refuse(api, L"CCB_DIRECTORY_PATH_UNSAFE");
        } else {
            if (status != ABSENT) return refuse(api, L"CCB_DIRECTORY_STALE_RECORD_CONFLICT");
            if (!property(api, L"JP_UPGRADABLE_FOUND", related)) return refuse(api, L"CCB_DIRECTORY_PRODUCT_OBSERVATION_FAILED");
            if (*related) {
                if (!guid(related) || same(related, product)) return refuse(api, L"CCB_DIRECTORY_MULTIPLE_OR_INVALID_OLD_PRODUCTS");
                if (api->old(api->context, related, name, upgrade, old, CAP) != PRESENT)
                    return refuse(api, L"CCB_DIRECTORY_LEGACY_OR_UNVERIFIED_UPGRADE");
                if (!canonical(old, selected)) return refuse(api, L"CCB_DIRECTORY_PATH_UNSAFE");
            } else if (*requested) {
                fresh = 1;
                if (!canonical(requested, selected)) return refuse(api, L"CCB_DIRECTORY_PATH_UNSAFE");
            } else {
                fresh = 1;
                if (!canonical(local, normalized) || StringCchPrintfW(prior, CAP, L"%s\\%sApp", normalized, name) != S_OK || !canonical(prior, selected))
                    return refuse(api, L"CCB_DIRECTORY_DEFAULT_UNAVAILABLE");
            }
        }
        if (*requested && (!canonical(requested, normalized) || !same(selected, normalized)))
            return refuse(api, L"CCB_DIRECTORY_REQUEST_CONFLICT");
    }
    /* Never target the data directory or the common LocalAppData container. */
    if (!canonical(local, normalized)) return refuse(api, L"CCB_DIRECTORY_CONTEXT_UNAVAILABLE");
    if (same(selected, normalized) || StringCchPrintfW(data, CAP, L"%s\\ChatChatBarDesktop", normalized) != S_OK)
        return refuse(api, L"CCB_DIRECTORY_DATA_ROOT_REFUSED");
    if (!_wcsnicmp(selected, data, wcslen(data)) && (!selected[wcslen(data)] || selected[wcslen(data)] == L'\\'))
        return refuse(api, L"CCB_DIRECTORY_DATA_ROOT_REFUSED");
    if (!canonical(windows, normalized)) return refuse(api, L"CCB_DIRECTORY_CONTEXT_UNAVAILABLE");
    if (!_wcsnicmp(selected, normalized, wcslen(normalized)) && (!selected[wcslen(normalized)] || selected[wcslen(normalized)] == L'\\'))
        return refuse(api, L"CCB_DIRECTORY_SYSTEM_ROOT_REFUSED");
    if (!safe_ancestors(api, selected)) return refuse(api, L"CCB_DIRECTORY_PATH_UNAVAILABLE_OR_REPARSE");
    if (fresh && api->empty(api->context, selected) != PRESENT) return refuse(api, L"CCB_DIRECTORY_UNOWNED_NONEMPTY_OR_UNAVAILABLE");
    if (!revalidate && (api->set(api->context, L"INSTALLDIR", selected) != PRESENT ||
        api->set(api->context, L"CcbResolvedDirectory", selected) != PRESENT))
        return refuse(api, L"CCB_DIRECTORY_PROPERTY_UNAVAILABLE");
    api->log(api->context, revalidate ? L"CCB_DIRECTORY_REVALIDATED" : L"CCB_DIRECTORY_RESOLVED");
    return ERROR_SUCCESS;
}

static int __stdcall real_get(void *context, const wchar_t *name, wchar_t *value, DWORD capacity) {
    DWORD size = capacity; UINT code = MsiGetPropertyW((MSIHANDLE)(UINT_PTR)context, name, value, &size);
    return code == ERROR_SUCCESS ? PRESENT : FAILED;
}
static int __stdcall real_set(void *context, const wchar_t *name, const wchar_t *value) {
    return MsiSetPropertyW((MSIHANDLE)(UINT_PTR)context, name, value) == ERROR_SUCCESS ? PRESENT : FAILED;
}
static int __stdcall real_saved(void *context, const wchar_t *name, const wchar_t *ver, const wchar_t *product, wchar_t *value, DWORD capacity) {
    wchar_t key[CAP], owner[CAP]; HKEY handle; DWORD type, bytes; LSTATUS code;
    (void)context;
    if (StringCchPrintfW(key, CAP, L"Software\\ChatChatBar\\%s\\%s", name, ver) != S_OK) return FAILED;
    code = RegOpenKeyExW(HKEY_CURRENT_USER, key, 0, KEY_QUERY_VALUE | KEY_WOW64_64KEY, &handle);
    if (code == ERROR_FILE_NOT_FOUND) return ABSENT;
    if (code != ERROR_SUCCESS) return FAILED;
    bytes = sizeof(owner);
    code = RegQueryValueExW(handle, L"ProductCode", NULL, &type, (BYTE *)owner, &bytes);
    if (code != ERROR_SUCCESS || type != REG_SZ || bytes < sizeof(wchar_t) || bytes > sizeof(owner) || bytes % sizeof(wchar_t) || owner[bytes / sizeof(wchar_t) - 1] || (wcslen(owner) + 1) * sizeof(wchar_t) != bytes || !same(owner, product)) {
        RegCloseKey(handle); return FAILED;
    }
    bytes = capacity * sizeof(wchar_t);
    code = RegQueryValueExW(handle, L"CcbInstallDirectoryV2", NULL, &type, (BYTE *)value, &bytes);
    RegCloseKey(handle);
    if (code != ERROR_SUCCESS || type != REG_SZ || bytes < sizeof(wchar_t) || bytes > capacity * sizeof(wchar_t) || bytes % sizeof(wchar_t) || value[bytes / sizeof(wchar_t) - 1] || (wcslen(value) + 1) * sizeof(wchar_t) != bytes) return FAILED;
    return PRESENT;
}
static int db_property(MSIHANDLE db, const wchar_t *name, wchar_t *value) {
    MSIHANDLE view = 0, record = 0; wchar_t query[CAP]; DWORD size = CAP; int ok = 0;
    StringCchPrintfW(query, CAP, L"SELECT `Value` FROM `Property` WHERE `Property`='%s'", name);
    if (MsiDatabaseOpenViewW(db, query, &view) == ERROR_SUCCESS && MsiViewExecute(view, 0) == ERROR_SUCCESS &&
        MsiViewFetch(view, &record) == ERROR_SUCCESS && MsiRecordGetStringW(record, 1, value, &size) == ERROR_SUCCESS) ok = 1;
    if (record) MsiCloseHandle(record);
    if (view) MsiCloseHandle(view);
    return ok;
}
static int __stdcall real_old(void *context, const wchar_t *product, const wchar_t *name, const wchar_t *upgrade, wchar_t *value, DWORD capacity) {
    wchar_t cache[CAP], old_name[CAP], old_upgrade[CAP], contract[CAP], ver[CAP], arp[CAP], saved[CAP];
    DWORD size = CAP; MSIHANDLE db = 0; int ok = 0;
    (void)context;
    if (MsiGetProductInfoExW(product, NULL, MSIINSTALLCONTEXT_USERUNMANAGED, INSTALLPROPERTY_LOCALPACKAGEW, cache, &size) != ERROR_SUCCESS) return FAILED;
    if (MsiOpenDatabaseW(cache, MSIDBOPEN_READONLY, &db) != ERROR_SUCCESS) return FAILED;
    if (db_property(db, L"ProductName", old_name) && same(name, old_name) &&
        db_property(db, L"UpgradeCode", old_upgrade) && same(upgrade, old_upgrade) &&
        db_property(db, L"CCB_DIRECTORY_CONTRACT", contract) && same(contract, L"2") &&
        db_property(db, L"ProductVersion", ver) && version(ver) &&
        real_saved(NULL, old_name, ver, product, saved, CAP) == PRESENT) {
        size = CAP;
        if (MsiGetProductInfoExW(product, NULL, MSIINSTALLCONTEXT_USERUNMANAGED, INSTALLPROPERTY_INSTALLLOCATIONW, arp, &size) == ERROR_SUCCESS &&
            canonical(arp, old_name) && canonical(saved, old_upgrade) && same(old_name, old_upgrade) &&
            StringCchCopyW(value, capacity, saved) == S_OK) ok = 1;
    }
    MsiCloseHandle(db);
    return ok ? PRESENT : FAILED;
}
static DWORD __stdcall real_attrs(void *context, const wchar_t *path, DWORD *error) {
    DWORD attributes; (void)context;
    attributes = GetFileAttributesW(path); *error = attributes == INVALID_FILE_ATTRIBUTES ? GetLastError() : ERROR_SUCCESS;
    return attributes;
}
static int __stdcall real_empty(void *context, const wchar_t *path) {
    wchar_t pattern[CAP]; WIN32_FIND_DATAW data; HANDLE search; DWORD attributes, error;
    (void)context;
    attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES) {
        error = GetLastError();
        return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND ? PRESENT : FAILED;
    }
    if (!(attributes & FILE_ATTRIBUTE_DIRECTORY) || (attributes & FILE_ATTRIBUTE_REPARSE_POINT) || StringCchPrintfW(pattern, CAP, L"%s\\*", path) != S_OK) return FAILED;
    search = FindFirstFileW(pattern, &data);
    if (search == INVALID_HANDLE_VALUE) return GetLastError() == ERROR_FILE_NOT_FOUND ? PRESENT : FAILED;
    do {
        if (wcscmp(data.cFileName, L".") && wcscmp(data.cFileName, L"..")) { FindClose(search); return ABSENT; }
    } while (FindNextFileW(search, &data));
    error = GetLastError(); FindClose(search);
    return error == ERROR_NO_MORE_FILES ? PRESENT : FAILED;
}
static void __stdcall real_log(void *context, const wchar_t *code) {
    MSIHANDLE record = MsiCreateRecord(1);
    if (record) { MsiRecordSetStringW(record, 0, code); MsiProcessMessage((MSIHANDLE)(UINT_PTR)context, INSTALLMESSAGE_INFO, record); MsiCloseHandle(record); }
}
static UINT run(MSIHANDLE handle, int revalidate) {
    Api api = {(void *)(UINT_PTR)handle, real_get, real_set, real_saved, real_old, real_attrs, real_empty, real_log};
    return resolve(&api, revalidate);
}
__declspec(dllexport) UINT __stdcall CcbResolveInstallDirectory(MSIHANDLE handle) { return run(handle, 0); }
__declspec(dllexport) UINT __stdcall CcbRevalidateInstallDirectory(MSIHANDLE handle) { return run(handle, 1); }
#ifdef CCB_GUARD_TEST
__declspec(dllexport) UINT __stdcall CcbTestDirectory(Api *api, int revalidate) { return resolve(api, revalidate); }
#endif
