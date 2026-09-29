import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import javax.imageio.ImageIO;

/**
 * PC helper for the panel report test (tool/src/test/.../comics/PanelReportTest.kt).
 * The Android unit-test classpath has no javax.imageio, so the test runs this with the JDK:
 *
 *   java PagePictures.java <outDir> <picture> [<picture> ...]
 *
 * For picture number i (0-based) it writes <outDir>/<i>.page: the picture's full width and
 * height (ints), then the picture shrunk the way the phone decodes it for panel detection
 * (by the biggest power of 2 that keeps it at least 800 px on its long side, each pixel the
 * average of the block it covers): its width and height, then its colors as ARGB ints.
 * A picture ImageIO can't read gets no file.
 */
public class PagePictures {
    private static final int GRID_LONG_SIDE = 800;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]);
        for (int i = 1; i < args.length; i++) {
            try {
                BufferedImage picture = ImageIO.read(new File(args[i]));
                if (picture == null) {
                    System.out.println("can't decode " + args[i]);
                    continue;
                }
                write(picture, new File(outDir, (i - 1) + ".page"));
            } catch (Exception e) {
                System.out.println("can't decode " + args[i] + ": " + e);
            }
        }
    }

    private static void write(BufferedImage picture, File file) throws Exception {
        int width = picture.getWidth();
        int height = picture.getHeight();
        // As ComicPageImages.findPanels: fitted to 800 px, then the power-of-2 sample size that keeps at least that.
        double scale = Math.min(1.0, (double) GRID_LONG_SIDE / Math.max(width, height));
        int gridWidth = Math.max(1, (int) Math.round(width * scale));
        int gridHeight = Math.max(1, (int) Math.round(height * scale));
        int sample = 1;
        while (width / (sample * 2) >= gridWidth && height / (sample * 2) >= gridHeight) sample *= 2;
        int sampledWidth = width / sample;
        int sampledHeight = height / sample;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(sampledWidth);
            out.writeInt(sampledHeight);
            int[] rows = new int[width * sample];
            for (int y = 0; y < sampledHeight; y++) {
                picture.getRGB(0, y * sample, width, sample, rows, 0, width);
                for (int x = 0; x < sampledWidth; x++) {
                    long red = 0, green = 0, blue = 0;
                    for (int dy = 0; dy < sample; dy++) {
                        for (int dx = 0; dx < sample; dx++) {
                            int c = rows[dy * width + x * sample + dx];
                            red += (c >> 16) & 0xFF;
                            green += (c >> 8) & 0xFF;
                            blue += c & 0xFF;
                        }
                    }
                    int n = sample * sample;
                    out.writeInt(0xFF000000 | (int) (red / n) << 16 | (int) (green / n) << 8 | (int) (blue / n));
                }
            }
        }
    }
}
