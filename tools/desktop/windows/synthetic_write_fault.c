/* Synthetic-only deferred failure probe. It observes actual new file/Registry
 * evidence and then fails; never writes resources, keys, credentials or cleanup. */
#define UNICODE
#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#define _WIN32_MSI 500
#include <windows.h>
#include <msi.h>
#include <msiquery.h>
#include <bcrypt.h>
#include <strsafe.h>
#include <wchar.h>
#include <wctype.h>

#define FCAP 4096
typedef int (__stdcall *FaultFile)(const wchar_t *,const wchar_t *);
typedef int (__stdcall *FaultRegistry)(const wchar_t *,const wchar_t *,const wchar_t *);
static int fguid(const wchar_t *s) {
    size_t i;if(wcslen(s)!=38||s[0]!=L'{'||s[37]!=L'}')return 0;
    for(i=1;i<37;i++){if(i==9||i==14||i==19||i==24){if(s[i]!=L'-')return 0;}else if(!iswxdigit(s[i]))return 0;}return 1;
}
static int fversion(const wchar_t *s){int dots=0,digits=0;while(*s){if(*s==L'.'){if(!digits||++dots>2)return 0;digits=0;}else if(*s>=L'0'&&*s<=L'9')digits++;else return 0;s++;}return dots==2&&digits>0;}
static int fpath(const wchar_t *s,int relative){
    size_t n=wcslen(s),i,start;wchar_t name[256],*dot; if(!n||n>240)return 0;
    if(!relative){if(n<4||!((s[0]>=L'A'&&s[0]<=L'Z')||(s[0]>=L'a'&&s[0]<=L'z'))||s[1]!=L':'||s[2]!=L'\\')return 0;start=3;}else start=0;
    for(i=start;i<=n;i++){
        if(s[i]&&(s[i]<32||wcschr(L"/:*?\"<>|%",s[i])))return 0;
        if(!s[i]||s[i]==L'\\'){
            size_t length=i-start;if(!length||length>=256||s[i-1]==L'.'||s[i-1]==L' '||StringCchCopyNW(name,256,s+start,length)!=S_OK)return 0;
            dot=wcschr(name,L'.');if(dot)*dot=0;
            if(!_wcsicmp(name,L"CON")||!_wcsicmp(name,L"PRN")||!_wcsicmp(name,L"AUX")||!_wcsicmp(name,L"NUL")||
                (wcslen(name)==4&&(!_wcsnicmp(name,L"COM",3)||!_wcsnicmp(name,L"LPT",3))&&name[3]>=L'1'&&name[3]<=L'9'))return 0;
            start=i+1;
        }
    }return 1;
}
static int evidence(const wchar_t *input,FaultFile file,FaultRegistry registry){
    wchar_t data[FCAP],*part[6],*p,path[FCAP];DWORD i,count=1;
    if(!input||!file||!registry||StringCchCopyW(data,FCAP,input)!=S_OK)return 0;
    part[0]=data;
    for(p=data;*p;p++)if(*p==L'|'){if(count>=6)return 0;*p=0;part[count++]=p+1;}
    if(count==6){size_t n=wcslen(part[3]);while(n>3&&part[3][n-1]==L'\\')part[3][--n]=0;}
    if(count!=6||wcscmp(part[0],L"CCB_SYNTHETIC_AFTER_WRITES_V1")||!fguid(part[1])||!fversion(part[2])||!fpath(part[3],0)||!fpath(part[4],1)||wcslen(part[5])!=64)return 0;
    for(i=0;i<64;i++)if(!iswxdigit(part[5][i]))return 0;
    if(StringCchPrintfW(path,FCAP,L"%s\\%s",part[3],part[4])!=S_OK)return 0;
    /* Both proofs are required; failure is never accepted as an intentional probe. */
    return registry(part[1],part[2],part[3])==1 && file(path,part[5])==1;
}
static int read_string(HKEY key,const wchar_t *name,wchar_t *out){
    DWORD size=FCAP*sizeof(wchar_t),type=0;LSTATUS status=RegQueryValueExW(key,name,NULL,&type,(BYTE *)out,&size);
    return status==ERROR_SUCCESS&&type==REG_SZ&&size>=2&&size<=FCAP*2&&size%2==0&&!out[size/2-1]&&(wcslen(out)+1)*2==size;
}
static int __stdcall real_registry(const wchar_t *product,const wchar_t *version,const wchar_t *root){
    HKEY user=NULL,key=NULL;wchar_t name[FCAP],value[FCAP];int ok=0;size_t n;
    if(StringCchPrintfW(name,FCAP,L"Software\\ChatChatBar\\ChatChatBarDesktopSynthetic\\%s",version)!=S_OK||
        RegOpenCurrentUser(KEY_READ|KEY_WOW64_64KEY,&user)||RegOpenKeyExW(user,name,0,KEY_QUERY_VALUE|KEY_WOW64_64KEY,&key))goto done;
    if(!read_string(key,L"ProductCode",value)||_wcsicmp(value,product)||!read_string(key,L"CcbInstallDirectoryV2",value))goto done;
    n=wcslen(value);while(n&&value[n-1]==L'\\')value[--n]=0;
    ok=!_wcsicmp(value,root);
done:if(key)RegCloseKey(key);if(user)RegCloseKey(user);return ok;
}
static int __stdcall real_file(const wchar_t *path,const wchar_t *expected){
    HANDLE file=INVALID_HANDLE_VALUE;BCRYPT_ALG_HANDLE alg=NULL;BCRYPT_HASH_HANDLE hash=NULL;BY_HANDLE_FILE_INFORMATION info;
    BYTE buffer[65536],digest[32];wchar_t text[65];DWORD read,total=0,i;int ok=0;
    file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,NULL);
    if(file==INVALID_HANDLE_VALUE||!GetFileInformationByHandle(file,&info)||info.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT)||info.nNumberOfLinks!=1||
        BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,NULL,0)||BCryptCreateHash(alg,&hash,NULL,0,NULL,0,0))goto done;
    for(;;){if(!ReadFile(file,buffer,sizeof(buffer),&read,NULL))goto done;if(!read)break;total+=read;if(total>512*1024*1024||BCryptHashData(hash,buffer,read,0))goto done;}
    if(!total||BCryptFinishHash(hash,digest,sizeof(digest),0))goto done;
    for(i=0;i<32;i++)if(StringCchPrintfW(text+i*2,65-i*2,L"%02x",digest[i])!=S_OK)goto done;
    ok=!_wcsicmp(text,expected);
done:SecureZeroMemory(buffer,sizeof(buffer));if(hash)BCryptDestroyHash(hash);if(alg)BCryptCloseAlgorithmProvider(alg,0);if(file!=INVALID_HANDLE_VALUE)CloseHandle(file);return ok;
}
static void message(MSIHANDLE install,const wchar_t *text){MSIHANDLE r=MsiCreateRecord(0);if(r){MsiRecordSetStringW(r,0,text);MsiProcessMessage(install,INSTALLMESSAGE_INFO,r);MsiCloseHandle(r);}}
__declspec(dllexport) UINT __stdcall CcbSyntheticFailAfterWrites(MSIHANDLE install){
    wchar_t data[FCAP];DWORD size=FCAP;
    if(!MsiGetMode(install,MSIRUNMODE_SCHEDULED)||MsiGetMode(install,MSIRUNMODE_ROLLBACK)||MsiGetMode(install,MSIRUNMODE_COMMIT)||
        MsiGetPropertyW(install,L"CustomActionData",data,&size)||!evidence(data,real_file,real_registry)){
        message(install,L"CCB_SYNTHETIC_FAULT_EVIDENCE_UNVERIFIED");return ERROR_INSTALL_FAILURE;
    }
    message(install,L"CCB_SYNTHETIC_NEW_FILE_AND_REGISTRY_OBSERVED");
    message(install,L"CCB_SYNTHETIC_INTENTIONAL_FAILURE_AFTER_WRITES");return ERROR_INSTALL_FAILURE;
}
#ifdef CCB_FAULT_TEST
__declspec(dllexport) int __stdcall CcbTestSyntheticWriteEvidence(const wchar_t *data,FaultFile file,FaultRegistry registry){return evidence(data,file,registry);}
__declspec(dllexport) int __stdcall CcbTestSyntheticFileHash(const wchar_t *path,const wchar_t *digest){return real_file(path,digest);}
#endif
