package es.ubu.batchdownloader.seo;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/** Dibuja tarjetas PNG locales sin descargar iconos ni ejecutar contenido del catálogo. */
@Component
public class SocialCardRenderer {
    public byte[] render(SeoService.Metadata metadata) {
        URI canonical = URI.create(metadata.canonicalUrl());
        String path = canonical.getPath();
        BufferedImage image = new BufferedImage(1_200, 630, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = image.createGraphics();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            canvas.setColor(new Color(0xF3F8F8));
            canvas.fillRect(0, 0, 1_200, 630);
            canvas.setColor(new Color(0x007F87));
            canvas.fillRect(0, 0, 18, 630);
            canvas.setColor(new Color(0xDDF1F0));
            canvas.fillOval(860, -190, 500, 500);
            drawIllustration(canvas, path);
            canvas.setColor(new Color(0x007F87));
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 27));
            canvas.drawString(SeoService.BRAND.toUpperCase(java.util.Locale.ROOT), 74, 94);
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            canvas.drawString(sectionLabel(path), 76, 145);
            canvas.setColor(new Color(0x122C36));
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 61));
            String title = metadata.title().replace(" | " + SeoService.BRAND, "");
            List<String> titleLines = wrap(canvas.getFontMetrics(), title, 940, 3);
            int baseline = 220;
            for (String line : titleLines) {
                canvas.drawString(line, 74, baseline);
                baseline += 73;
            }
            canvas.setColor(new Color(0x49626B));
            canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 27));
            baseline += 10;
            for (String line : wrap(canvas.getFontMetrics(), metadata.description(), 1_030, 3)) {
                canvas.drawString(line, 76, baseline);
                baseline += 38;
            }
            String footer = footerLabel(path);
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            canvas.setColor(new Color(0x007F87));
            canvas.fillRoundRect(74, 551, canvas.getFontMetrics().stringWidth(footer) + 40, 43, 21, 21);
            canvas.setColor(Color.WHITE);
            canvas.drawString(footer, 94, 579);
            canvas.setColor(new Color(0x49626B));
            canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
            String host = canonical.getHost();
            canvas.drawString(host, 1_126 - canvas.getFontMetrics().stringWidth(host), 579);
        } finally {
            canvas.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("png_writer_unavailable");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("social_image_unavailable", exception);
        }
    }

    private static String sectionLabel(String path) {
        if (path.startsWith("/catalog/app/")) return "APLICACIÓN DEL CATÁLOGO";
        if (path.startsWith("/bundles/")) return "BUNDLE DE APLICACIONES";
        return switch (path) {
            case "/catalog" -> "CATÁLOGO DE APLICACIONES";
            case "/catalog/tags" -> "EXPLORA POR CATEGORÍA";
            case "/catalog/editors" -> "DESCUBRE SUS CREADORES";
            case "/terms", "/privacy" -> "INFORMACIÓN LEGAL";
            case "/" -> "DESCARGAS EN UN SOLO LUGAR";
            default -> "TU ESPACIO EN BATCH DOWNLOADER";
        };
    }

    private static String footerLabel(String path) {
        if (path.startsWith("/catalog/app/")) return "FUENTES Y OPCIONES DE DESCARGA";
        if (path.startsWith("/bundles/")) return "TUS APLICACIONES EN UN SOLO ZIP";
        return switch (path) {
            case "/catalog/tags" -> "ETIQUETAS DEL CATÁLOGO";
            case "/catalog/editors" -> "EDITORES DE APLICACIONES";
            case "/terms" -> "TÉRMINOS Y CONDICIONES";
            case "/privacy" -> "POLÍTICA DE PRIVACIDAD";
            default -> "WINDOWS   ·   LINUX   ·   MACOS";
        };
    }

    private static void drawIllustration(Graphics2D canvas, String path) {
        if (path.startsWith("/catalog/app/")) {
            canvas.setColor(new Color(0x007F87));
            canvas.fillRoundRect(954, 40, 160, 113, 14, 14);
            canvas.setColor(Color.WHITE);
            canvas.fillRoundRect(959, 65, 150, 83, 9, 9);
            canvas.fillOval(967, 50, 6, 6);
            canvas.fillOval(980, 50, 6, 6);
            canvas.fillOval(993, 50, 6, 6);
            canvas.setColor(new Color(0xDDF1F0));
            canvas.fillRoundRect(973, 83, 32, 47, 5, 5);
            canvas.setColor(new Color(0x007F87));
            canvas.fillRoundRect(1017, 88, 70, 7, 4, 4);
            canvas.fillRoundRect(1017, 105, 52, 7, 4, 4);
        } else if (path.startsWith("/bundles/")) {
            drawBox(canvas, 998, 49, 34);
            drawBox(canvas, 962, 101, 34);
            drawBox(canvas, 1_034, 101, 34);
        } else {
            drawBox(canvas, 978, 68, 50);
        }
    }

    private static void drawBox(Graphics2D canvas, int x, int y, int size) {
        canvas.setColor(new Color(0xE8AC62));
        canvas.fill(new Polygon(new int[]{x, x + size, x + size * 2, x + size}, new int[]{y, y - size / 2, y, y + size / 2}, 4));
        canvas.setColor(new Color(0xD8954C));
        canvas.fill(new Polygon(new int[]{x, x + size, x + size, x}, new int[]{y, y + size / 2, y + size * 3 / 2, y + size}, 4));
        canvas.setColor(new Color(0xBD783B));
        canvas.fill(new Polygon(new int[]{x + size, x + size * 2, x + size * 2, x + size}, new int[]{y + size / 2, y, y + size, y + size * 3 / 2}, 4));
    }

    private static List<String> wrap(FontMetrics metrics, String text, int width, int limit) {
        List<String> lines = new ArrayList<>();
        String remaining = text.strip();
        while (!remaining.isEmpty() && lines.size() < limit) {
            if (metrics.stringWidth(remaining) <= width) {
                lines.add(remaining);
                break;
            }
            int end = remaining.length();
            while (end > 0 && metrics.stringWidth(remaining.substring(0, end) + "…") > width) {
                end = remaining.offsetByCodePoints(end, -1);
            }
            if (end == 0) break;
            if (lines.size() == limit - 1) {
                lines.add(remaining.substring(0, end).stripTrailing() + "…");
                break;
            }
            int space = remaining.lastIndexOf(' ', end);
            if (space > 0) end = space;
            lines.add(remaining.substring(0, end).stripTrailing());
            remaining = remaining.substring(end).stripLeading();
        }
        return lines;
    }
}
