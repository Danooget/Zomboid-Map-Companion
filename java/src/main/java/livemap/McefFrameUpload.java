package livemap;

import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CinemaMod BGRA -> RGBA texture uploads, including 4K UHD.
 * Only two resolution textures are kept to avoid unbounded VRAM use.
 * Rendering is budgeted by the selected performance preset.
 * Frame arrays and GPU upload scratch buffers are reused; only the newest
 * pending frame is uploaded, with at most one queued GPU task.
 */
public final class McefFrameUpload {
    private static final AtomicReference<Frame> pending = new AtomicReference<>();
    private static final AtomicInteger uploads = new AtomicInteger();
    private static final AtomicBoolean renderQueued = new AtomicBoolean();
    private static final ConcurrentLinkedQueue<byte[]> reusableCopies = new ConcurrentLinkedQueue<>();
    private static final AtomicLong lastAcceptedNanos = new AtomicLong();
    // The cache and scratch buffers are ONLY touched from Zomboid's render context.
    private static final LinkedHashMap<Long, CachedTexture> cache =
            new LinkedHashMap<>(4, 0.75f, true);
    private static final int MAX_CACHED_TEXTURES = 2;

    private static volatile Texture visibleTexture;
    private static volatile int targetWidth = 800;
    private static volatile int targetHeight = 600;
    private static volatile boolean reportedError;

    private record Frame(byte[] bgra, int width, int height) { }
    private record CachedTexture(Texture texture, ByteBuffer rgba) { }
    private McefFrameUpload() { }

    public static Texture getTexture() { return visibleTexture; }

    public static void setTargetSize(int width, int height) {
        if (width < 320 || height < 240 || width > 3840 || height > 2160) {
            throw new IllegalArgumentException("Unsupported browser resolution: " + width + "x" + height);
        }
        if (targetWidth == width && targetHeight == height) return;
        targetWidth = width;
        targetHeight = height;
        Frame discarded = pending.getAndSet(null);
        if (discarded != null) recycle(discarded.bgra());
        reusableCopies.clear();
        lastAcceptedNanos.set(0L);
        System.out.println("[LiveWebMap-MCEF] Framebuffer target: " + width + "x" + height);
    }

    private static long frameInterval() {
        int fps = LiveWebMapPanel.isRenderActive()
                ? LiveWebMapPerformance.targetFps() : 1;
        return 1_000_000_000L / Math.max(1, fps);
    }

    private static void recycle(byte[] bytes) {
        if (bytes == null || bytes.length != (long) targetWidth * targetHeight * 4) return;
        if (reusableCopies.size() < 2) reusableCopies.offer(bytes);
    }

    /** Copy native pixels before CEF invalidates its ByteBuffer; drop old frames. */
    public static void acceptPaint(ByteBuffer nativeBGRA, int width, int height) {
        if (nativeBGRA == null || width != targetWidth || height != targetHeight) return;
        long length = (long) width * height * 4;
        if (length > Integer.MAX_VALUE || nativeBGRA.capacity() < length) return;
        long now = System.nanoTime();
        long previous = lastAcceptedNanos.get();
        if (previous != 0L && now - previous < frameInterval()) return;
        if (!lastAcceptedNanos.compareAndSet(previous, now)) return;

        byte[] copy = reusableCopies.poll();
        if (copy == null || copy.length != length) copy = new byte[(int) length];
        ByteBuffer view = nativeBGRA.duplicate();
        view.clear();
        try {
            view.get(copy);
        } catch (Throwable e) {
            recycle(copy);
            throw e;
        }
        Frame old = pending.getAndSet(new Frame(copy, width, height));
        if (old != null) recycle(old.bgra());
    }

    /** Called from the game tick; GL work is performed on the render context thread. */
    public static void uploadLatest() {
        if (!renderQueued.compareAndSet(false, true)) return;
        Frame frame = pending.getAndSet(null);
        if (frame == null) {
            renderQueued.set(false);
            return;
        }
        if (frame.width() != targetWidth || frame.height() != targetHeight) {
            recycle(frame.bgra());
            renderQueued.set(false);
            return;
        }
        try {
            final int width = frame.width();
            final int height = frame.height();
            final byte[] bgra = frame.bgra();
            RenderThread.queueInvokeOnRenderContext(() -> {
                try {
                    if (width != targetWidth || height != targetHeight) return;
                    long key = (((long) width) << 32) | (height & 0xffffffffL);
                    CachedTexture item = cache.get(key);
                    if (item == null) {
                        Texture texture = new Texture(width, height, 0);
                        int stride = texture.getWidthHW();
                        int hwHeight = texture.getHeightHW();
                        if (stride < width || hwHeight < height ||
                                stride > 4096 || hwHeight > 4096) {
                            texture.destroy();
                            throw new IllegalStateException("Unexpected backing texture: "
                                    + stride + "x" + hwHeight);
                        }
                        ByteBuffer scratch;
                        try {
                            scratch = ByteBuffer.allocateDirect(stride * hwHeight * 4);
                        } catch (Throwable e) {
                            texture.destroy();
                            throw new IllegalStateException("Unable to allocate RGBA upload buffer", e);
                        }
                        item = new CachedTexture(texture, scratch);
                        cache.put(key, item);
                        System.out.println("[LiveWebMap-MCEF] Texture allocated: " + width
                                + "x" + height + " (backing " + stride + "x" + hwHeight + ")");
                    }
                    Texture target = item.texture();
                    ByteBuffer rgba = item.rgba();
                    rgba.clear();
                    int stride = target.getWidthHW();
                    for (int y = 0; y < height; y++) {
                        rgba.position(y * stride * 4);
                        int row = y * width * 4;
                        for (int x = 0; x < width; x++) {
                            int i = row + x * 4;
                            rgba.put(bgra[i + 2]);
                            rgba.put(bgra[i + 1]);
                            rgba.put(bgra[i]);
                            rgba.put(bgra[i + 3]);
                        }
                    }
                    rgba.position(0);
                    rgba.limit(rgba.capacity());
                    target.setData(rgba);
                    visibleTexture = target;

                    // Keep active and one previous texture only. Destroy old resources
                    // on the render thread, never from Chromium's paint callback.
                    while (cache.size() > MAX_CACHED_TEXTURES) {
                        Iterator<Map.Entry<Long, CachedTexture>> iter = cache.entrySet().iterator();
                        Map.Entry<Long, CachedTexture> oldest = iter.next();
                        iter.remove();
                        oldest.getValue().texture().destroy();
                    }
                    int count = uploads.incrementAndGet();
                    if (count <= 3 || count % 120 == 0) {
                        System.out.println("[LiveWebMap-MCEF] TEXTURE UPLOAD #" + count
                                + " at " + width + "x" + height);
                    }
                } catch (Throwable e) {
                    logFailure("RENDER UPLOAD FAILED", e);
                } finally {
                    recycle(bgra);
                    renderQueued.set(false);
                }
            });
        } catch (Throwable e) {
            recycle(frame.bgra());
            renderQueued.set(false);
            logFailure("FRAME QUEUE FAILED", e);
        }
    }

    private static synchronized void logFailure(String message, Throwable e) {
        if (reportedError) return;
        reportedError = true;
        System.err.println("[LiveWebMap-MCEF] " + message + ": " + e);
        e.printStackTrace(System.err);
    }
}
