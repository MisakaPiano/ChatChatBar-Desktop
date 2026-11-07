import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class UpdateArgvProbe {
    static void run(String label, String python, String script, String runtime, String root, String cache, String routes) throws Exception {
        var args = new ArrayList<>(List.of(python, "-B", script, "--package", root, "--cache", cache,
                "--proxy-routes", routes, "--download", "--expected-version", "0.1.0-preview.1245"));
        var process = new ProcessBuilder(args).directory(Path.of(runtime).toFile()).redirectErrorStream(true).start();
        var output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS) || output.length > 65536) throw new IllegalStateException("SYNTHETIC_CHILD_BOUND");
        System.out.println(label + "\t" + process.exitValue() + "\t" + Base64.getEncoder().encodeToString(output));
    }
    public static void main(String[] args) throws Exception {
        String routes = Files.readString(Path.of(args[6]), StandardCharsets.UTF_8);
        System.out.println("JVM\t" + System.getProperty("java.version") + "\t" + System.getProperty("jdk.lang.Process.allowAmbiguousCommands"));
        run("original1244", args[0], args[1], args[3], args[4], args[5], routes);
        run("argvOnly", args[0], args[2], args[3], args[4], args[5], routes);
    }
}
