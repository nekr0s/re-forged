/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.gui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import forge.gui.PlayMats.PlayMat;

/** Decodes the play mat images for the Swing GUI. */
public final class PlayMatArt {
    /** Small enough to keep for every mat in the folder; the full art is not cached here. */
    private static final Map<String, BufferedImage> THUMBNAILS = new HashMap<>();

    private PlayMatArt() { }

    /**
     * The mat art at full size, or {@code null} if it can't be read. Not cached — callers that
     * repaint keep the result themselves, so an unused mat costs no memory.
     */
    public static BufferedImage load(final PlayMat mat) {
        try {
            return ImageIO.read(mat.getFile());
        } catch (final IOException | RuntimeException e) {
            System.err.println("Could not read play mat " + mat.getFile() + ": " + e);
            return null;
        }
    }

    /** Mat art scaled to fill the given box, cropping the overflow. Cached per mat and size. */
    public static BufferedImage thumbnail(final PlayMat mat, final int w, final int h) {
        final String cacheKey = mat.getKey() + '@' + w + 'x' + h;
        if (THUMBNAILS.containsKey(cacheKey)) {
            return THUMBNAILS.get(cacheKey);
        }
        final BufferedImage source = load(mat);
        BufferedImage thumb = null;
        if (source != null) {
            thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            final Graphics2D g2d = thumb.createGraphics();
            drawScaledToFill(g2d, source, w, h);
            g2d.dispose();
        }
        THUMBNAILS.put(cacheKey, thumb);
        return thumb;
    }

    /**
     * Draws the image centred and scaled up until it covers the whole box, so a mat never
     * letterboxes however the field is resized.
     */
    public static void drawScaledToFill(final Graphics2D g2d, final BufferedImage image,
            final int w, final int h) {
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        final double scale = Math.max(w / (double) image.getWidth(), h / (double) image.getHeight());
        final int drawnW = (int) Math.ceil(image.getWidth() * scale);
        final int drawnH = (int) Math.ceil(image.getHeight() * scale);
        g2d.drawImage(image, (w - drawnW) / 2, (h - drawnH) / 2, drawnW, drawnH, null);
    }
}
