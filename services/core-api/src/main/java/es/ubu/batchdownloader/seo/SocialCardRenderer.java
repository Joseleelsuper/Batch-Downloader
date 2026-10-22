package es.ubu.batchdownloader.seo;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/** Sirve tarjetas fijas precompiladas y dibuja las que dependen del catálogo. */
@Component
public class SocialCardRenderer {
    private static final BufferedImage APP_ICON = icon("app.png");
    private static final List<BufferedImage> PLATFORM_ICONS = List.of(
            icon("windows.png"), icon("linux.png"), icon("macos.png"));

    public byte[] render(SeoService.Metadata metadata) {
        URI canonical = URI.create(metadata.canonicalUrl());
        if (SeoService.PUBLIC_PAGES.containsKey(canonical.getPath())
                && canonical.getRawQuery() == null
                && metadata.canonicalUrl().equals(SeoStaticAssets.BASE_URL + canonical.getPath())) {
            return SeoStaticAssets.resource("/seo/static-cards/"
                    + SeoStaticAssets.cardName(canonical.getPath()) + ".png");
        }
        return renderDynamic(metadata);
    }

    byte[] renderDynamic(SeoService.Metadata metadata) {
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
            canvas.drawImage(APP_ICON, 963, 24, 150, 150, null);
            canvas.setColor(new Color(0x007F87));
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            canvas.drawString(sectionLabel(path), 76, 100);
            canvas.setColor(new Color(0x122C36));
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 66));
            String title = "/".equals(path) ? SeoService.BRAND
                    : metadata.title().replace(" | " + SeoService.BRAND, "");
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
            if (footer == null) {
                canvas.setColor(Color.WHITE);
                canvas.fillRoundRect(74, 543, 196, 54, 25, 25);
                for (int index = 0; index < PLATFORM_ICONS.size(); index++) {
                    canvas.drawImage(PLATFORM_ICONS.get(index), 91 + index * 61, 553, 34, 34, null);
                }
            } else {
                canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
                canvas.setColor(new Color(0x007F87));
                canvas.fillRoundRect(74, 551, canvas.getFontMetrics().stringWidth(footer) + 40, 43, 21, 21);
                canvas.setColor(Color.WHITE);
                canvas.drawString(footer, 94, 579);
            }
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
        if (path.startsWith(SeoService.APP_PREFIX)) return "APLICACIÓN DEL CATÁLOGO";
        if (path.startsWith(SeoService.BUNDLE_PREFIX)) return "BUNDLE DE APLICACIONES";
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
        if (path.startsWith(SeoService.APP_PREFIX)) return "FUENTES Y OPCIONES DE DESCARGA";
        if (path.startsWith(SeoService.BUNDLE_PREFIX)) return "TUS APLICACIONES EN UN SOLO ZIP";
        return switch (path) {
            case "/catalog/tags" -> "ETIQUETAS DEL CATÁLOGO";
            case "/catalog/editors" -> "EDITORES DE APLICACIONES";
            case "/terms" -> "TÉRMINOS Y CONDICIONES";
            case "/privacy" -> "POLÍTICA DE PRIVACIDAD";
            default -> null;
        };
    }

    private static BufferedImage icon(String name) {
        try (InputStream source = SocialCardRenderer.class.getResourceAsStream("/seo/cards/" + name)) {
            if (source == null) throw new IllegalStateException("social_icon_missing: " + name);
            BufferedImage image = ImageIO.read(source);
            if (image == null) throw new IllegalStateException("social_icon_invalid: " + name);
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("social_icon_unavailable: " + name, exception);
        }
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
            if (end == 0) return lines;
            if (lines.size() == limit - 1) {
                lines.add(remaining.substring(0, end).stripTrailing() + "…");
                return lines;
            }
            int space = remaining.lastIndexOf(' ', end);
            if (space > 0) end = space;
            lines.add(remaining.substring(0, end).stripTrailing());
            remaining = remaining.substring(end).stripLeading();
        }
        return lines;
    }
}
