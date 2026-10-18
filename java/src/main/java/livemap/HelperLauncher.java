
package livemap;

import java.io.IOException;

public final class HelperLauncher {
    private HelperLauncher() {}

    public static void testLaunch() {
        try {
            Process process = new ProcessBuilder(
                "notepad.exe"
            ).start();

            System.out.println(
                "[LiveWebMap] Helper process launched. PID: "
                + process.pid()
            );
        } catch (IOException | SecurityException error) {
            System.err.println(
                "[LiveWebMap] Helper launch failed: " + error
            );
        }
    }
}
