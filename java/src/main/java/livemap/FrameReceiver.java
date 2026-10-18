
package livemap;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

public final class FrameReceiver {
    public static final int WIDTH = 800;
    public static final int HEIGHT = 600;
    public static final int FRAME_BYTES = WIDTH * HEIGHT * 4;

    private static final AtomicReference<byte[]> latestFrame =
        new AtomicReference<>();

    private static volatile boolean started = false;

    private FrameReceiver() {}

    public static synchronized void start() {
        if (started) return;
        started = true;

        Thread thread = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(
                45872, 1, java.net.InetAddress.getLoopbackAddress()
            )) {
                System.out.println(
                    "[LiveWebMap] Frame receiver listening on 127.0.0.1:45872"
                );

                while (true) {
                    try (Socket socket = server.accept();
                         DataInputStream input =
                             new DataInputStream(socket.getInputStream())) {

                        System.out.println(
                            "[LiveWebMap] Browser helper connected"
                        );

                        while (true) {
                            int width = input.readInt();
                            int height = input.readInt();

                            if (width != WIDTH || height != HEIGHT) {
                                throw new IllegalArgumentException(
                                    "Unexpected frame size: "
                                    + width + "x" + height
                                );
                            }

                            byte[] frame = new byte[FRAME_BYTES];
                            input.readFully(frame);
                            latestFrame.set(frame);
                        }
                    } catch (Exception error) {
                        System.out.println(
                            "[LiveWebMap] Helper disconnected: " + error
                        );
                    }
                }
            } catch (Exception error) {
                System.err.println(
                    "[LiveWebMap] Frame receiver failed: " + error
                );
                started = false;
            }
        }, "LiveWebMap-FrameReceiver");

        thread.setDaemon(true);
        thread.start();
    }

    public static byte[] takeLatestFrame() {
        return latestFrame.getAndSet(null);
    }
}
