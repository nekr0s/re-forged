package forge.gui.framework;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.Objects;

import javax.swing.border.EmptyBorder;

import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinnedLabel;

/**
 * The tab label object in drag layout.
 * No modification should be necessary to this object.
 * Simply call the constructor with a title string argument.
 */
@SuppressWarnings("serial")
public final class DragTab extends SkinnedLabel implements ILocalRepaint {
    private boolean selected = false;
    private int priority = 10;
    private float flashIntensity = 0f;
    private Color borderOverride = null;

    /**
     * The tab label object in drag layout.
     * No modification should be necessary to this object.
     * Simply call the constructor with a title string argument.
     * 
     * @param title0 &emsp; {java.lang.String}
     */
    public DragTab(final String title0) {
        super(title0);
        setToolTipText(title0);
        setOpaque(false);
        setSelected(false);
        setBorder(new EmptyBorder(2, 5, 2, 5));
        this.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));

        this.addMouseListener(SRearrangingUtil.getRearrangeClickEvent());
        this.addMouseMotionListener(SRearrangingUtil.getRearrangeDragEvent());
    }

    /** @param isSelected0 &emsp; boolean */
    public void setSelected(final boolean isSelected0) {
        selected = isSelected0;
        repaintSelf();
    }

    /** True when this is the active tab in its DragCell. */
    public boolean isSelected() {
        return selected;
    }

    /** Decreases display priority of this tab in relation to its siblings in an overflow case. */
    public void priorityDecrease() {
        priority++;
    }

    /** Sets this tab as first to be displayed if siblings overflow. */
    public void priorityOne() {
        priority = 1;
    }

    /**
     * Returns display priority of this tab in relation to its siblings in an overflow case.
     * @return int
     */
    public int getPriority() {
        return priority;
    }

    // There should be no need for this method.
    @SuppressWarnings("unused")
    private void setPriority() {
        // Intentionally empty.
    }

    /** Outlines the tab in a fixed colour instead of the skin one; null restores the skin colour. */
    public void setBorderOverride(final Color clr0) {
        if (Objects.equals(borderOverride, clr0)) { return; }
        borderOverride = clr0;
        repaintSelf();
    }

    /** Sets the red flash overlay intensity (0..1) and repaints. */
    public void setFlashIntensity(final float intensity) {
        flashIntensity = Math.max(0f, Math.min(1f, intensity));
        repaintSelf();
    }

    @Override
    public void repaintSelf() {
        final Dimension d = DragTab.this.getSize();
        repaint(0, 0, d.width, d.height);
    }

    @Override
    public void paintComponent(final Graphics g) {
        FSkin.setGraphicsColor(g, FSkin.getColor(selected ? FSkin.Colors.CLR_ACTIVE : FSkin.Colors.CLR_INACTIVE));
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() * 2, 6, 6);
        drawTabBorder(g);

        if (flashIntensity > 0f) {
            g.setColor(new Color(1f, 0f, 0f, flashIntensity));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() * 2, 6, 6);
        }

        super.paintComponent(g);
    }

    private void drawTabBorder(final Graphics g) {
        if (borderOverride == null) {
            FSkin.setGraphicsColor(g, FSkin.getColor(FSkin.Colors.CLR_BORDERS));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() * 2, 6, 6);
            return;
        }

        // Inset by half the stroke so it lands entirely inside the tab, and restore the
        // stroke afterwards since the label text is painted with the same Graphics.
        final Graphics2D g2d = (Graphics2D) g;
        final Stroke oldStroke = g2d.getStroke();
        final int inset = DragCell.HIGHLIGHT_BORDER_T / 2;
        g2d.setColor(borderOverride);
        g2d.setStroke(new BasicStroke(DragCell.HIGHLIGHT_BORDER_T));
        g2d.drawRoundRect(inset, inset, getWidth() - 1 - DragCell.HIGHLIGHT_BORDER_T, getHeight() * 2, 6, 6);
        g2d.setStroke(oldStroke);
    }
}
