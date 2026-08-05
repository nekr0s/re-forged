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
package forge.screens.match.views;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import forge.PlayerMat;
import forge.gui.PlayMatArt;
import forge.gui.PlayMats;
import forge.gui.PlayMats.PlayMat;
import forge.toolbox.FSkin.SkinnedPanel;

/**
 * The surface drawn under a player's battlefield.
 * <p>
 * Sits behind the (fully transparent) battlefield scroll pane, so cards and
 * overlays paint on top of it. Renders either one of the {@link PlayerMat}
 * preset colours or one of the images in {@link PlayMats}, whichever the mat key
 * names.
 */
@SuppressWarnings("serial")
public class PlayerMatPanel extends SkinnedPanel {
    /** Lift applied to the middle of the mat so it doesn't read as a flat slab. */
    private static final float SHEEN_ALPHA = 0.10f;
    /** Darkening towards the edges. */
    private static final float VIGNETTE_ALPHA = 0.38f;
    /** Flat dim over image mats, so the art doesn't compete with the cards on top of it. */
    private static final float IMAGE_SCRIM_ALPHA = 0.30f;

    private String matKey = PlayerMat.DEFAULT_KEY;
    /** The preset the key names, or null when it names an image. */
    private PlayerMat preset = PlayerMat.SLATE;
    /** The image the key names, or null when it names a preset. */
    private PlayMat image;
    /** Decoded art for {@link #image}, kept so a resize doesn't go back to disk. */
    private BufferedImage imageArt;

    /** The mat drawn once at the current size, rebuilt when either of those changes. */
    private BufferedImage rendered;

    public PlayerMatPanel() {
        // Stays non-opaque even when a mat is drawn: paintComponent covers every
        // pixel itself, and claiming opacity without guaranteeing that in every
        // state is what produces repaint artifacts.
        setOpaque(false);
    }

    /**
     * @param key a {@link PlayerMat} name or a {@link PlayMats} image key; anything else
     *            falls back to the default colour, which is what a player whose mat file
     *            this machine doesn't have should look like
     */
    public void setMatKey(final String key0) {
        final String key = key0 == null || key0.isEmpty() ? PlayerMat.DEFAULT_KEY : key0;
        if (key.equals(matKey)) { return; }
        matKey = key;

        preset = PlayerMat.fromKey(key);
        image = preset == null ? PlayMats.fromKey(key) : null;
        if (preset == null && image == null) {
            preset = PlayerMat.SLATE;
        }
        imageArt = null;
        rendered = null;
        repaint();
    }

    public String getMatKey() {
        return matKey;
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final int w = getWidth();
        final int h = getHeight();
        if ((preset != null && preset.isTransparent()) || w <= 0 || h <= 0) {
            super.paintComponent(g);
            return;
        }

        // The battlefield above this is transparent, so anything moving over it —
        // a card being dragged, a card flying in — repaints the mat behind it every
        // frame. Rendering the surface once and blitting keeps that cheap.
        if (rendered == null || rendered.getWidth() != w || rendered.getHeight() != h) {
            rendered = render(w, h);
        }
        g.drawImage(rendered, 0, 0, null);
    }

    private BufferedImage render(final int w, final int h) {
        final BufferedImage surface = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g2d = surface.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (image != null) {
            paintImage(g2d, w, h);
        } else {
            g2d.setColor(new Color(preset.getRgb()));
            g2d.fillRect(0, 0, w, h);
        }

        // A soft centre sheen plus an edge vignette give the surface some depth,
        // which is what separates "a mat" from "a coloured rectangle".
        final float radius = Math.max(w, h) * 0.75f;
        final Point2D centre = new Point2D.Float(w / 2f, h / 2f);
        g2d.setPaint(new RadialGradientPaint(centre, radius,
                new float[] {0f, 1f},
                new Color[] {new Color(1f, 1f, 1f, SHEEN_ALPHA), new Color(1f, 1f, 1f, 0f)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g2d.fillRect(0, 0, w, h);

        g2d.setPaint(new RadialGradientPaint(centre, radius,
                new float[] {0.55f, 1f},
                new Color[] {new Color(0f, 0f, 0f, 0f), new Color(0f, 0f, 0f, VIGNETTE_ALPHA)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g2d.fillRect(0, 0, w, h);

        // Thin lighter edge, like the stitching on a real playmat.
        g2d.setColor(new Color(1f, 1f, 1f, 0.12f));
        g2d.drawRect(0, 0, w - 1, h - 1);

        g2d.dispose();
        return surface;
    }

    /** Falls back to the default colour if the file has gone missing or won't decode. */
    private void paintImage(final Graphics2D g2d, final int w, final int h) {
        if (imageArt == null) {
            imageArt = PlayMatArt.load(image);
        }
        if (imageArt == null) {
            image = null;
            preset = PlayerMat.SLATE;
            g2d.setColor(new Color(preset.getRgb()));
            g2d.fillRect(0, 0, w, h);
            return;
        }
        PlayMatArt.drawScaledToFill(g2d, imageArt, w, h);
        g2d.setColor(new Color(0f, 0f, 0f, IMAGE_SCRIM_ALPHA));
        g2d.fillRect(0, 0, w, h);
    }
}
