import java.io.File;
import java.util.Arrays;

/** Synthetic cloud regression only: the production ProcessBuilder/CRT boundary, no application. */
public final class UpdateCommandBridge {
    public static void main(String[] args) throws Exception {
        Process process = new ProcessBuilder(Arrays.asList(args))
            .directory(new File(args[0]).getParentFile()).redirectErrorStream(true).start();
        process.getInputStream().transferTo(System.out);
        System.exit(process.waitFor());
    }
}
