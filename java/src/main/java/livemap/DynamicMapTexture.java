
package livemap;

import zombie.core.textures.Texture;
import zombie.core.opengl.RenderThread;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class DynamicMapTexture {
    private static Texture texture;

    private DynamicMapTexture() {}

    public static Texture getTexture() {
        if (texture == null) {
            texture = new Texture(800, 600, 0);

            System.out.println(
                "[LiveWebMap] Dynamic texture created: "
                + texture.getWidthHW() + "x"
                + texture.getHeightHW()
            );
        }

        byte[] frame = FrameReceiver.takeLatestFrame();

        if (frame != null) {
            final Texture target = texture;
            final int backingWidth = target.getWidthHW();
            final int backingHeight = target.getHeightHW();

            ByteBuffer pixels = ByteBuffer.allocateDirect(
                backingWidth * backingHeight * 4
            ).order(ByteOrder.nativeOrder());

            // Copy each 800-pixel row into the larger backing texture.
            for (int y = 0; y < FrameReceiver.HEIGHT; y++) {
                pixels.position(y * backingWidth * 4);
                pixels.put(
                    frame,
                    y * FrameReceiver.WIDTH * 4,
                    FrameReceiver.WIDTH * 4
                );
            }

            pixels.position(0);
            pixels.limit(pixels.capacity());

            RenderThread.queueInvokeOnRenderContext(() -> {
                target.setData(pixels);
            });
        }

        return texture;
    }
}
