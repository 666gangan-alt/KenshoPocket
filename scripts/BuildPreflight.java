import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only checks; source-file launch requires only the bundled JDK, not Gradle or dependencies. */
class BuildPreflight {
    private static boolean failed;

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Expected project and SDK directories");
        Path userDirectory = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        System.out.println("JAVA_USER_HOME=" + userDirectory);
        if (userDirectory.equals(userDirectory.getRoot())) {
            failed = true;
            System.err.println("FAIL: Java user.home must not be the drive root.");
        }
        check("PROJECT", Path.of(args[0]));
        check("JAVA_USER_HOME", userDirectory);
        check("GRADLE_USER_HOME", Path.of(System.getenv("GRADLE_USER_HOME")));
        check("ANDROID_USER_HOME", Path.of(System.getenv("ANDROID_USER_HOME")));
        check("ANDROID_JAR", Path.of(args[1]).resolve("platforms/android-36/android.jar"));
        if (failed) {
            System.err.println("The build cannot safely continue in this execution environment. No permissions were changed.");
            System.exit(2);
        }
        System.out.println("BUILD_PREFLIGHT_OK");
    }

    private static void check(String label, Path path) {
        try {
            if (!Files.exists(path)) throw new java.io.IOException("Path does not exist");
            if (!Files.isReadable(path)) throw new java.io.IOException("Path is not readable");
            path.toRealPath();
            System.out.println("OK " + label);
        } catch (Exception error) {
            failed = true;
            System.err.println("FAIL " + label + ": " + error.getClass().getSimpleName() + " / " + path);
        }
    }
}
