import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public final class PrepareUserIcon {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: PrepareUserIcon <source-png>");
        }
        BufferedImage source = ImageIO.read(new File(args[0]));
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage square = source.getSubimage(x, y, side, side);

        writeScaled(square, "app/src/main/res/mipmap-mdpi/ic_launcher.png", 48, 0);
        writeScaled(square, "app/src/main/res/mipmap-hdpi/ic_launcher.png", 72, 0);
        writeScaled(square, "app/src/main/res/mipmap-xhdpi/ic_launcher.png", 96, 0);
        writeScaled(square, "app/src/main/res/mipmap-xxhdpi/ic_launcher.png", 144, 0);
        writeScaled(square, "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", 192, 0);
        writeScaled(square, "app/src/main/res/drawable-nodpi/ic_launcher_foreground.png", 432, 54);
    }

    private static void writeScaled(BufferedImage source, String path, int size, int padding) throws Exception {
        File file = new File(path);
        file.getParentFile().mkdirs();
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, padding, padding, size - padding * 2, size - padding * 2, null);
        g.dispose();
        ImageIO.write(target, "png", file);
    }
}
