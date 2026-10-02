import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class MakeIcon {
    public static void main(String[] a) throws Exception {
        int S = 1024;
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 背景：深色渐变圆角方块
        float r = S * 0.22f;
        RoundRectangle2D.Float bg = new RoundRectangle2D.Float(0, 0, S, S, r, r);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1C2534), 0, S, new Color(0x0A0E14)));
        g.fill(bg);
        g.setClip(bg);

        // 右上蓝色辉光
        g.setPaint(new RadialGradientPaint(new Point2D.Float(S * 0.85f, S * 0.12f),
                S * 0.6f, new float[]{0f, 1f},
                new Color[]{new Color(0x553FDCEC, true), new Color(0x003FDCEC, true)}));
        g.fillRect(0, 0, S, S);

        // 仪表屏：圆角矩形 + 蓝色描边
        float mx = S * 0.13f, my = S * 0.26f, mw = S * 0.74f, mh = S * 0.48f;
        RoundRectangle2D.Float screen = new RoundRectangle2D.Float(mx, my, mw, mh, mh * 0.18f, mh * 0.18f);
        g.setPaint(new GradientPaint(mx, my, new Color(0x11151D), mx, my + mh, new Color(0x05070B)));
        g.fill(screen);
        g.setStroke(new BasicStroke(S * 0.018f));
        g.setPaint(new Color(0x3FDCEC));
        g.draw(screen);

        // 仪表刻度弧（白）+ 蓝色指针（裁剪进屏幕内）
        float cx = mx + mw / 2, cy = my + mh * 0.58f, rad = mh * 0.38f;
        Shape oldClip = g.getClip();
        g.setClip(screen);
        g.setStroke(new BasicStroke(S * 0.016f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(new Color(0xE8EEF5));
        g.draw(new Arc2D.Float(cx - rad, cy - rad, rad * 2, rad * 2, 150, 240, Arc2D.OPEN));
        double ang = Math.toRadians(55);
        g.setPaint(new Color(0x3FDCEC));
        g.setStroke(new BasicStroke(S * 0.020f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(cx, cy,
                (float)(cx + rad * Math.cos(ang)), (float)(cy - rad * Math.sin(ang))));
        g.fill(new Ellipse2D.Float(cx - S * 0.018f, cy - S * 0.018f,
                S * 0.036f, S * 0.036f));
        g.setClip(oldClip);

        // 音符（蓝）
        float nx = S * 0.30f, ny = S * 0.20f;
        g.setPaint(new Color(0x3FDCEC));
        float nw = S * 0.075f, nh = S * 0.055f;
        g.fill(new Ellipse2D.Float(nx, ny + S * 0.28f, nw, nh));
        g.setStroke(new BasicStroke(S * 0.022f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(nx + nw, ny + S * 0.30f, nx + nw, ny - S * 0.02f));
        QuadCurve2D flag = new QuadCurve2D.Float(
                nx + nw, ny - S * 0.02f,
                nx + nw + S * 0.11f, ny + S * 0.05f,
                nx + nw + S * 0.045f, ny + S * 0.16f);
        g.draw(flag);

        g.dispose();
        ImageIO.write(img, "png", new File(a.length > 0 ? a[0] : "cc_launcher_1024.png"));
        System.out.println("icon written");
    }
}
