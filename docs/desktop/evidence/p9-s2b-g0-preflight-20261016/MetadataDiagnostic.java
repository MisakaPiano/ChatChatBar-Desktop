import java.nio.file.*;
import java.nio.file.attribute.*;
class MetadataDiagnostic {
 public static void main(String[] args) throws Exception {
  Path p=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
  while(p!=null){BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   System.out.println("component="+p.getFileName()+"; key="+a.fileKey()+"; directory="+a.isDirectory()+"; other="+a.isOther()+"; link="+a.isSymbolicLink());p=p.getParent();}
 }
}
