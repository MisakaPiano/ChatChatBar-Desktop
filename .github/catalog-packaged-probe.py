"""Cloud-only synthetic JDBC probe over the exact audited ordinary image; never packaged."""
from pathlib import Path
import hashlib,json,os,subprocess,zipfile
assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='MisakaPiano/ChatChatBar-Desktop'
base=Path(os.environ['CCB_BUILD_BASE']).resolve(strict=True);image=Path(os.environ['CCB_CANDIDATE_IMAGE']).resolve(strict=True);output=Path(os.environ['CCB_BUILD_OUTPUT']).resolve(strict=True)
assert base.parent==Path(base.anchor) and base.name.startswith('ccbpack-') and image.is_relative_to(base) and output.parent==base
manifest=json.loads((Path(os.environ['CCB_CANDIDATE_NSIS'])/'install-manifest.json').read_text(encoding='utf-8-sig'))
assert manifest['version']==os.environ['CCB_CANDIDATE_VERSION'] and manifest['commit']==os.environ['CCB_CANDIDATE_SOURCE']
rows={r['path']:r for r in manifest['files']};jars=list((image/'app').glob('*.jar'));sqlite=[p for p in jars if p.name.startswith('sqlite-jdbc-')];assert len(sqlite)==1
entry='com/example/chatbar/desktop/DesktopNovelAiSqlDatabase.class';main=[]
for jar in jars:
    with zipfile.ZipFile(jar) as z:
        if entry in z.namelist():main.append(jar)
assert len(main)==1
proof=[]
for path in [main[0],sqlite[0]]:
    r=rows[path.relative_to(image).as_posix()]
    with path.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
    assert path.stat().st_size==r['bytes'] and actual==r['sha256'];proof.append(dict(path=path.relative_to(image).as_posix(),bytes=r['bytes'],sha256=actual))
case=base/'packaged-sqlite-probe';case.mkdir();source=case/'PackagedCatalogProbe.java';classes=case/'classes';classes.mkdir()
source.write_text(r'''
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
public final class PackagedCatalogProbe {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]).resolve("\u5408\u6210 path # % &"); Files.createDirectories(root);
    Path file=root.resolve("sample.sqlite");
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE sample(value TEXT)"); s.execute("INSERT INTO sample VALUES ('synthetic')");
    }
    byte[] original=Files.readAllBytes(file);
    Files.setAttribute(file,"dos:readonly",true);
    Class<?> adapter=Class.forName("com.example.chatbar.desktop.DesktopNovelAiSqlDatabase");
    Class<?> cursor=Class.forName("com.example.chatbar.domain.image.NovelAiSqlCursor");
    Method query=adapter.getMethod("rawQuery",String.class,String[].class);
    Object db=adapter.getConstructor(Path.class).newInstance(file);
    try {
      Object row=query.invoke(db,"SELECT value FROM sample",new String[0]);
      try {
        if(!Boolean.TRUE.equals(cursor.getMethod("moveToFirst").invoke(row))) throw new AssertionError("NO_ROW");
        if(!"synthetic".equals(cursor.getMethod("getString",int.class).invoke(row,0))) throw new AssertionError("WRONG_ROW");
      } finally {cursor.getMethod("close").invoke(row);}
      boolean refused=false;
      try {query.invoke(db,"DELETE FROM sample",new String[0]);}
      catch(InvocationTargetException expected) {if(!(expected.getCause() instanceof SQLException)) throw expected;refused=true;}
      if(!refused) throw new AssertionError("READONLY_WRITE_NOT_REFUSED");
    } finally {adapter.getMethod("close").invoke(db);Files.setAttribute(file,"dos:readonly",false);}
    if(!Arrays.equals(original,Files.readAllBytes(file))) throw new AssertionError("SOURCE_CHANGED");
    Path missing=root.resolve("missing.sqlite");boolean refused=false;
    try {adapter.getConstructor(Path.class).newInstance(missing);}
    catch(InvocationTargetException expected) {if(!(expected.getCause() instanceof IllegalStateException)) throw expected;refused=true;}
    if(!refused||Files.exists(missing)) throw new AssertionError("MISSING_WAS_CREATED");
    System.out.println("PACKAGED_CATALOG_SQLITE_READONLY_PASS");
  }
}
''',encoding='ascii')
classpath=str(image/'app/*')
commands=[['javac','-encoding','UTF-8','-d',str(classes),str(source)],['java','-cp',str(classes)+os.pathsep+classpath,'PackagedCatalogProbe',str(case)]]
results=[]
for index,command in enumerate(commands):
    result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    (output/('packaged-sqlite-'+str(index)+'.log')).write_text(result.stdout+'\n'+result.stderr,encoding='utf-8')
    assert result.returncode==0
    if index==1:assert result.stdout.strip()=='PACKAGED_CATALOG_SQLITE_READONLY_PASS'
    results.append(dict(step='compile-unpackaged-companion' if index==0 else 'actual-packaged-adapter-and-driver',exitCode=result.returncode))
receipt=dict(kind='ACTUAL_ORDINARY_PACKAGED_SQLITE_ADAPTER_DRIVER_PROBE',passed=True,version=manifest['version'],source=manifest['commit'],jarProofs=proof,companionOutsideImage=True,syntheticOnly=True,unicodeSpacesPercentHashAmpersandPath=True,readOnlyFileAndWriteRefusal=True,missingDatabaseNotCreated=True,originalBytesUnchanged=True,steps=results,userRootAccessed=False,providerCalls=False,humanAcceptance=False)
(output/'packaged-sqlite-probe.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8');print(json.dumps(receipt))
