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
 * For picture number i (0-based) it writes <outDir>/<i>.page: the picture's width and height
 * (ints), its gray levels (one byte a pixel, full size, the same weights as grayFromArgb),
 * then a color preview shrunk to 800 px on its long side: width, height, RGB bytes.
 * A picture ImageIO can't read gets no file.
 */
public class PagePictures {
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
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(width);
            out.writeInt(height);
            int[] row = new int[width];
            byte[] gray = new byte[width];
            for (int y = 0; y < height; y++) {
                picture.getRGB(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    int c = row[x];
                    gray[x] = (byte) ((((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000);
                }
                out.write(gray);
            }
            double scale = Math.min(1.0, 800.0 / Math.max(width, height));
            int previewWidth = (int) Math.round(width * scale);
            int previewHeight = (int) Math.round(height * scale);
            out.writeInt(previewWidth);
            out.writeInt(previewHeight);
            for (int y = 0; y < previewHeight; y++) {
                for (int x = 0; x < previewWidth; x++) {
                    int c = picture.getRGB(Math.min(width - 1, (int) (x / scale)), Math.min(height - 1, (int) (y / scale)));
                    out.write((c >> 16) & 0xFF);
                    out.write((c >> 8) & 0xFF);
                    out.write(c & 0xFF);
                }
            }
        }
    }
}
