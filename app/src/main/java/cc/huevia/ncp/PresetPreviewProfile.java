package cc.huevia.ncp;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Phone-side colour approximation shared by legacy NCP and Flexible Color NP3. */
public interface PresetPreviewProfile {
    String registrationName();
    String modelVersion();
    double mapTone(double normalized);
    /** Point colour transform only; neighbourhood effects are applied by renderPixels. */
    int applyArgb(int argb);

    default SpatialPreviewFilter.Settings spatialSettings() { return SpatialPreviewFilter.Settings.NONE; }

    /** Shared, bounded-memory path for thumbnails and full-size photographs. */
    default void renderPixels(SpatialPreviewFilter.Pixels pixels, BooleanSupplier cancel,
            DoubleConsumer progress) throws IOException {
        SpatialPreviewFilter.apply(pixels, spatialSettings(), this::applyArgb, 100, cancel, progress);
    }

    /** A separate, bounded output for thumbnails; full-size photos use bounded strips. */
    default int[] render(int[] argb, int width, int height) {
        long pixels = (long) width * height;
        if (argb == null || width <= 0 || height <= 0 || pixels > NcpPreviewProfile.MAX_PIXELS
                || pixels != argb.length) throw new IllegalArgumentException("Invalid preview dimensions.");
        int[] output = argb.clone();
        try {
            renderPixels(new SpatialPreviewFilter.Pixels() {
                public int width() { return width; }
                public int height() { return height; }
                public void read(int[] target, int row, int rows) {
                    System.arraycopy(output, row * width, target, 0, rows * width);
                }
                public void write(int[] source, int row, int rows) {
                    System.arraycopy(source, 0, output, row * width, rows * width);
                }
            }, () -> false, null);
        } catch (IOException failure) {
            throw new IllegalStateException("预设预览处理未完成。", failure);
        }
        return output;
    }
}
