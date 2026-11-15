import java.nio.file.*;

class StartupPathEvidence {
    private static String quoted(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    public static void main(String[] args) throws Exception {
        Path created = Files.createTempDirectory("ccb-lan-path-proof-").toAbsolutePath();
        try {
            Path real = created.toRealPath(LinkOption.NOFOLLOW_LINKS);
            boolean shortName = false;
            for (Path part : created) if (part.toString().matches(".*~[0-9].*")) shortName = true;
            System.out.println("{\"javaTmpdir\":" + quoted(System.getProperty("java.io.tmpdir")) +
                ",\"createdRoot\":" + quoted(created.toString()) + ",\"realRoot\":" + quoted(real.toString()) +
                ",\"fileStore\":" + quoted(Files.getFileStore(created).type()) +
                ",\"sameIgnoringCase\":" + created.toString().equalsIgnoreCase(real.toString()) +
                ",\"shortNameComponent\":" + shortName + ",\"symbolicLink\":" + Files.isSymbolicLink(created) + "}");
        } finally { Files.delete(created); }
    }
}
