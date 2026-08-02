/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Nate
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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import forge.CachedCardImage;
import forge.game.GameView;
import forge.game.card.CardView.CardStateView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.gamemodes.match.YieldUpdate;
import forge.gui.GuiBase;
import forge.gui.UiCommand;
import forge.gui.card.CardDetailUtil;
import forge.gui.card.CardDetailUtil.DetailColors;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.IVDoc;
import forge.interfaces.IGameController;
import forge.localinstance.skin.FSkinProp;
import forge.player.AutoYieldStore.TriggerDecision;
import forge.screens.match.CardFlight;
import forge.screens.match.controllers.CDock.ArcState;
import forge.screens.match.controllers.CStack;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FMouseAdapter;
import forge.toolbox.FScrollPane;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinnedTextArea;
import forge.util.Localizer;
import forge.util.collect.FCollectionView;
import forge.view.arcane.CardPanel;
import net.miginfocom.swing.MigLayout;

/**
 * Assembles Swing components of stack report.
 *
 * <br><br><i>(V at beginning of class name denotes a view class.)</i>
 */
public class VStack implements IVDoc<CStack> {

    /** Height of the strip naming the player who put the item on the stack. */
    private static final int HEADER_HEIGHT = 17;
    /**
     * The one size every card in the cascade is drawn at. Fixed on purpose: a
     * cascade that resized with its window made the cards jump about, so a stack
     * too tall for the screen scrolls instead of shrinking.
     */
    private static final int CARD_WIDTH = 245;
    private static final int CARD_HEIGHT = Math.round(CARD_WIDTH * CardPanel.ASPECT_RATIO);
    /** Fraction of a card left uncovered by the item cascaded over it. */
    private static final float PEEK = 0.12f;
    /** How far down the cascade each item sits from the one before it. */
    private static final int STEP = HEADER_HEIGHT + Math.round(CARD_HEIGHT * PEEK);
    /** Share of the screen height a window sizing itself to the stack stops growing at. */
    private static final float MAX_HEIGHT_FRACTION = 0.72f;
    /** Smallest width the text column is given. */
    private static final int TEXT_PANEL_WIDTH = 250;
    /** Space between the cascade and the text column. */
    private static final int COLUMN_GAP = 6;
    /** Size of the title-bar control that opens the text view. */
    private static final int TOGGLE_SIZE = 21;
    /** One layer of the drop shadow under an expanded text row. */
    private static final Color SHADOW_TINT = new Color(0, 0, 0, 40);
    /** Height of the buttons that hand priority back to the game. */
    private static final int RESOLVE_HEIGHT = 26;

    // Fields used with interface IVDoc
    private DragCell parentCell;
    private final DragTab tab = new DragTab(Localizer.getInstance().getMessage("lblStack"));

    // Top-level containers
    private final StackPanel stackPanel = new StackPanel();
    @SuppressWarnings("serial")
    private final FScrollPane scroller = new FScrollPane(stackPanel, false,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
        @Override
        public Dimension getPreferredSize() {
            // The cascade runs as long as it likes now that it scrolls instead of
            // shrinking, so cap what it can ask a window that packs around it for.
            final Dimension size = super.getPreferredSize();
            size.height = Math.min(size.height, maxCascadeHeight());
            return size;
        }
    };
    private final FLabel btnToggleText;
    private final FButton btnResolveTop =
            new FButton(Localizer.getInstance().getMessage("lblResolveTopOfStack"));
    private final FButton btnResolveAll =
            new FButton(Localizer.getInstance().getMessage("lblYieldToEntireStack"));

    // Other fields
    private final AbilityMenu abilityMenu = new AbilityMenu();

    /** Ids of the items the stack showed last update, so the new arrivals stand out. */
    private final Set<Integer> lastItemIds = new HashSet<>();
    /** Items added by the last update, waiting to be flown in once they are on screen. */
    private final List<Arrival> arrivals = new ArrayList<>();

    /**
     * An item that has just gone on the stack, with the place its card was cast
     * from. The origin has to be taken while the stack is being rebuilt, because
     * by the time the window is up and the flight can start, the hand has redrawn
     * without the card and there is nothing left to fly out of.
     */
    private record Arrival(StackItemPanel panel, Rectangle origin) { }

    /** The item the targeting arc and the card detail are pointed at. */
    private StackItemPanel hoveredItem;
    /** The item the mouse is actually over, which is drawn clear of the cascade. */
    private StackItemPanel raisedItem;
    /** Whether the text column is open. */
    private boolean showText;

    public StackItemPanel getHoveredItem() {
        return hoveredItem;
    }

    private final CStack controller;
    public VStack(final CStack controller) {
        this.controller = controller;

        btnToggleText = new FLabel.ButtonBuilder()
                .icon(FSkin.getIcon(FSkinProp.ICO_DECKLIST))
                .iconScaleAuto(true)
                .tooltip(Localizer.getInstance().getMessage("lblStack"))
                .selectable()
                .cmdClick((UiCommand) this::toggleTextList)
                .build();
        btnToggleText.setPreferredSize(new Dimension(TOGGLE_SIZE, TOGGLE_SIZE));

        //same buttons the prompt uses, in the same accent colour
        for (final FButton btn : new FButton[] { btnResolveTop, btnResolveAll }) {
            btn.setTint(VPrompt.ACCENT);
            btn.setFont(FSkin.getBoldFont(12));
            btn.setMargin(new Insets(0, 6, 0, 6)); //the stack is narrower than the prompt
        }
        btnResolveTop.setCommand((UiCommand) this::resolveTopOfStack);
        btnResolveAll.setCommand((UiCommand) this::resolveEntireStack);

        //one notch of the wheel uncovers one more card
        scroller.getVerticalScrollBar().setUnitIncrement(STEP);
    }

    @Override
    public void populate() {
        populateInto(parentCell.getBody());
    }

    /**
     * Lays the cascade out into the given container. Used both for the docked
     * cell and for {@link FloatingStack}, so the two share one set of components.
     */
    public void populateInto(final JPanel container) {
        container.removeAll();
        container.setLayout(new MigLayout("insets 0, gap 0"));
        //one scroll pane over both columns, so the cards and their text scroll together
        container.add(scroller, "cell 0 0, grow, push");
        //no fixed share of the width: both labels are set, so each button keeps the
        //room its own text needs and they split whatever the cascade leaves over
        container.add(btnResolveTop, "cell 0 1, growx, gaptop 4, h " + RESOLVE_HEIGHT + "!, split 2");
        container.add(btnResolveAll, "growx, gapleft 4, h " + RESOLVE_HEIGHT + "!");
    }

    /** The title-bar control that opens and closes the text view. */
    public FLabel getTextToggle() {
        return btnToggleText;
    }

    /** Opens or closes the plain-text view of the stack alongside the cards. */
    private void toggleTextList() {
        showText = !showText;
        refreshLayout();
        controller.stackLayoutChanged();
    }

    /** How tall the cascade may make a window that is sizing itself to the stack. */
    private static int maxCascadeHeight() {
        return Math.round(Toolkit.getDefaultToolkit().getScreenSize().height * MAX_HEIGHT_FRACTION);
    }

    /**
     * Passes priority once, which lets only the item on top of the stack resolve.
     * No yield is set, so the next item stops for the player again.
     */
    private void resolveTopOfStack() {
        controller.getMatchUI().getGameController().passPriority();
    }

    /** Lets the whole stack resolve, as the right-click menu's entry of the same name does. */
    private void resolveEntireStack() {
        final PlayerView local = controller.getMatchUI().getCurrentPlayer();
        if (local == null) { return; }
        controller.getMatchUI().getGameController().sendYieldUpdate(new YieldUpdate.StackYield(local, true, false));
        controller.getMatchUI().getGameController().passPriority();
    }

    /** Re-runs the layout after something other than the stack contents changed. */
    private void refreshLayout() {
        stackPanel.arrange();
        stackPanel.revalidate();
        stackPanel.repaint();
    }

    @Override
    public void setParentCell(final DragCell cell0) {
        parentCell = cell0;
    }

    @Override
    public DragCell getParentCell() {
        return parentCell;
    }

    @Override
    public EDocID getDocumentID() {
        return EDocID.REPORT_STACK;
    }

    @Override
    public DragTab getTabLabel() {
        return tab;
    }

    @Override
    public CStack getLayoutControl() {
        return controller;
    }

    public void updateStack() {
        final GameView model = controller.getMatchUI().getGameView();

        if (model == null) {
            return;
        }

        final FCollectionView<StackItemView> items = model.getStack();
        tab.setText(Localizer.getInstance().getMessage("lblStack") + " : " + items.size());

        final Iterable<StackItemView> safeItems = controller.getMatchUI().isNetGame()
                ? items.threadSafeIterable() : items;

        // An item that has gone is one that resolved or was countered, so note where
        // it sat: whatever it turns into next flies out of there.
        final CardFlight flight = controller.getMatchUI().getCardFlight();
        final Set<Integer> currentIds = new HashSet<>();
        for (final StackItemView item : safeItems) {
            currentIds.add(item.getId());
        }
        for (final StackItemPanel panel : stackPanel.items) {
            if (!currentIds.contains(panel.getItem().getId())) {
                flight.recordDeparture(panel.getItem().getSourceCard(), panel.getCardBoundsOnScreen());
            }
        }

        hoveredItem = null;
        raisedItem = null;
        arrivals.clear();
        stackPanel.clear();

        boolean isFirst = true;
        for (final StackItemView item : safeItems) {
            final StackItemPanel panel = new StackItemPanel(item);
            stackPanel.addItem(panel, new StackTextRow(panel));
            if (!lastItemIds.contains(item.getId())) {
                // Taken now, while the card is still in the hand it was cast from.
                final Rectangle origin = flight.originOf(item.getSourceCard(), true);
                if (origin != null) {
                    arrivals.add(new Arrival(panel, origin));
                }
            }

            //update the Card Picture/Detail when the spell is added to the stack
            if (isFirst) {
                isFirst = false;
                controller.getMatchUI().setCard(item.getSourceCard());
            }
        }
        lastItemIds.clear();
        lastItemIds.addAll(currentIds);
        stackPanel.markPlayerRuns();

        // Default the targeting arc to the item resolving next, as the list did.
        setHovered(stackPanel.items.isEmpty() ? null : stackPanel.items.get(0));

        // The foot of the cascade is where the action is — both the item that just
        // arrived and the one about to resolve are down there — so every update
        // ends up parked on it rather than on the oldest item. Deferred, because
        // the scrollbar's range only reflects the new contents once the panel
        // below has laid out again.
        final JScrollBar bar = scroller.getVerticalScrollBar();
        SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
        refreshLayout();
    }

    /**
     * Flies whatever the last update added to the stack in from wherever it was
     * cast. Called once the stack window is up, since the flight ends on the
     * item's place on screen and that isn't settled until then.
     */
    public void animateArrivals() {
        if (arrivals.isEmpty()) { return; }
        final List<Arrival> pending = new ArrayList<>(arrivals);
        arrivals.clear();
        SwingUtilities.invokeLater(() -> {
            for (final Arrival arrival : pending) {
                final Rectangle target = arrival.panel().getCardBoundsOnScreen();
                if (target != null) {
                    controller.getMatchUI().getCardFlight().fly(arrival.panel().getItem().getSourceCard(),
                            arrival.origin(), target.getLocation(), target.width);
                }
            }
        });
    }

    /**
     * Brings an item out from under the ones cascaded over it, and opens its text
     * out to its full height alongside. Driven by the mouse over either column.
     */
    private void setRaised(final StackItemPanel panel, final boolean raised) {
        if (raised) {
            raisedItem = panel;
        } else if (raisedItem == panel) {
            raisedItem = null;
        } else {
            return; //another item took over already
        }
        stackPanel.arrange();
        stackPanel.repaint();
    }

    /** What the mouse entering either column of an item does. */
    private void onItemEntered(final StackItemPanel panel) {
        if (controller.getMatchUI().getCDock().getArcState() == ArcState.MOUSEOVER) {
            setHovered(panel);
        }
        controller.getMatchUI().setCard(panel.getItem().getSourceCard());
        setRaised(panel, true);
    }

    /** What the mouse leaving either column of an item does. */
    private void onItemExited(final StackItemPanel panel) {
        if (controller.getMatchUI().getCDock().getArcState() == ArcState.MOUSEOVER
                && hoveredItem == panel) {
            setHovered(null);
        }
        setRaised(panel, false);
    }

    /** Points the targeting arc, the card detail and the text list at one item. */
    private void setHovered(final StackItemPanel panel) {
        hoveredItem = panel;
        final int index = panel == null ? -1 : stackPanel.items.indexOf(panel);
        for (int i = 0; i < stackPanel.rows.size(); i++) {
            stackPanel.rows.get(i).setHighlighted(i == index);
        }
    }

    /**
     * Both columns of the stack in one scrollable panel: the cards cascaded down
     * the left so each covers all but the top sliver of the one before it, and
     * each item's text level with its card on the right.
     */
    @SuppressWarnings("serial")
    private class StackPanel extends JPanel implements Scrollable {
        /** The items in stack order — first is the one resolving next. */
        private final List<StackItemPanel> items = new ArrayList<>();
        /** The text of each item, in the same order. */
        private final List<StackTextRow> rows = new ArrayList<>();

        StackPanel() {
            setLayout(null); //items are positioned by hand, and deliberately overlap
            setOpaque(false);
        }

        void clear() {
            items.clear();
            rows.clear();
            removeAll();
        }

        void addItem(final StackItemPanel panel, final StackTextRow row) {
            items.add(panel);
            rows.add(row);
            add(panel);
            add(row);
        }

        /**
         * Leaves the name on only the first card of each run by the same player.
         * The cascade reads top to bottom from the last item to the first, so a
         * run starts at whichever of its cards is nearest the top.
         */
        void markPlayerRuns() {
            for (int i = 0; i < items.size(); i++) {
                items.get(i).showHeaderText = i == items.size() - 1
                        || !items.get(i + 1).headerText.equals(items.get(i).headerText);
            }
        }

        @Override
        public void doLayout() {
            arrange();
        }

        void arrange() {
            final int count = items.size();
            if (count == 0) {
                setPreferredSize(new Dimension(0, 0));
                return;
            }

            final int availWidth = getWidth();
            final int totalHeight = (count - 1) * STEP + HEADER_HEIGHT + CARD_HEIGHT;

            // The top of the stack sits lowest, where nothing covers it. Cards that
            // gave up their name strip give up the space for it too, so the ones above
            // them run straight into them instead of leaving a blank band.
            final int[] tops = new int[count];
            for (int i = 0; i < count; i++) {
                final StackItemPanel panel = items.get(i);
                tops[i] = (count - 1 - i) * STEP + HEADER_HEIGHT - panel.headerHeight();
                panel.setBounds(0, tops[i], CARD_WIDTH, panel.headerHeight() + CARD_HEIGHT);
            }

            final int textX = CARD_WIDTH + COLUMN_GAP;
            final int textWidth = Math.max(TEXT_PANEL_WIDTH, availWidth - textX);
            for (int i = 0; i < count; i++) {
                final StackTextRow row = rows.get(i);
                row.setVisible(showText);
                if (!showText) { continue; }
                // Each row starts level with the top of its own card, name strip and all,
                // so an item that dropped its strip moves its text up by the same amount.
                final int y = tops[i];
                // Every row but the bottom one reaches only as far as the next row down;
                // the bottom one is level with the fully visible card, so it gets the rest.
                int rowHeight = Math.max(HEADER_HEIGHT, (i == 0 ? totalHeight : tops[i - 1] - 2) - y);
                row.setRaised(items.get(i) == raisedItem); //before measuring: it changes the insets
                if (row.isRaised()) {
                    // Hovering opens the row out over the ones below it, but only downwards
                    // and only as far as the cascade goes: moving it would drag it out from
                    // under the pointer, and growing the panel would shift everything else.
                    rowHeight = Math.max(rowHeight, Math.min(totalHeight - y, row.wrappedHeight(textWidth)));
                }
                row.setBounds(textX, y, textWidth, rowHeight);
            }
            arrangeZOrder();

            final Dimension preferred = new Dimension(showText ? textX + textWidth : CARD_WIDTH, totalHeight);
            if (!preferred.equals(getPreferredSize())) {
                setPreferredSize(preferred); //re-runs layout once, then settles
            }
        }

        /** Swing paints children back to front, so the item under the mouse ends up on top. */
        void arrangeZOrder() {
            final List<Component> order = new ArrayList<>();
            final int raised = raisedItem == null ? -1 : items.indexOf(raisedItem);
            if (raised >= 0) {
                order.add(rows.get(raised));
                order.add(items.get(raised));
            }
            for (int i = 0; i < items.size(); i++) {
                if (i == raised) { continue; }
                order.add(rows.get(i));
                order.add(items.get(i));
            }
            for (int z = 0; z < order.size(); z++) {
                setComponentZOrder(order.get(z), z);
            }
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(final Rectangle visible, final int orientation, final int direction) {
            return STEP;
        }

        @Override
        public int getScrollableBlockIncrement(final Rectangle visible, final int orientation, final int direction) {
            return Math.max(STEP, visible.height - STEP);
        }

        /** Both columns are laid out to the width on offer, so there is nothing to scroll sideways. */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** One item on the stack: who put it there, and the card it came from. */
    @SuppressWarnings("serial")
    public class StackItemPanel extends JPanel {
        private final StackItemView item;
        private final Color headerColor;
        private final String headerText;
        /** False while an item above this one already names the same player. */
        private boolean showHeaderText = true;

        private CachedCardImage cachedImage;

        public StackItemView getItem() {
            return item;
        }

        /** Zero for the items that dropped their name, since the strip goes with it. */
        int headerHeight() {
            return showHeaderText ? HEADER_HEIGHT : 0;
        }

        StackItemPanel(final StackItemView item0) {
            item = item0;
            setOpaque(false);

            final boolean optional = item.isOptionalTrigger()
                    && controller.getMatchUI().isLocalPlayer(item.getActivatingPlayer());
            final PlayerView activator = item.getActivatingPlayer();
            headerText = (optional ? "(OPTIONAL) " : "") + (activator == null ? "" : activator.getName());

            // TODO: A hacky workaround is currently used to make the game not leak the color information for Morph cards.
            final CardStateView curState = item.getSourceCard().getCurrentState();
            final boolean isFaceDown = item.getSourceCard().isFaceDown();
            final DetailColors color = isFaceDown ? CardDetailUtil.DetailColors.FACE_DOWN : CardDetailUtil.getBorderColor(curState, true); // otherwise doesn't work correctly for face down Morphs
            headerColor = new Color(color.r, color.g, color.b);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(final MouseEvent e) {
                    onItemEntered(StackItemPanel.this);
                }

                @Override
                public void mouseExited(final MouseEvent e) {
                    onItemExited(StackItemPanel.this);
                }

                @Override
                public void mouseClicked(final MouseEvent e) {
                    if (controller.getMatchUI().getCDock().getArcState() == ArcState.ON) {
                        if (hoveredItem == StackItemPanel.this) {
                            setHovered(null);
                        }
                        else
                        {
                            setHovered(StackItemPanel.this);
                            controller.getMatchUI().setCard(item.getSourceCard());
                        }
                    }
                }
            });

            addMouseListener(new FMouseAdapter() {
                @Override
                public void onLeftClick(final MouseEvent e) {
                    onClick(e);
                }
                @Override
                public void onRightClick(final MouseEvent e) {
                    onClick(e);
                }
                private void onClick(final MouseEvent e) {
                    abilityMenu.setStackInstance(item);
                    boolean hasVisibleItem = false;
                    for (Component c : abilityMenu.getComponents()) {
                        if (c.isVisible()) {
                            hasVisibleItem = true;
                            break;
                        }
                    }
                    if (!hasVisibleItem) {
                        return;
                    }
                    abilityMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            });
        }

        /** Requests the card image at the one size the cascade draws it at. */
        void refreshImage() {
            final float screenScale = GuiBase.getInterface().getScreenScale();
            cachedImage = new CachedCardImage(item.getSourceCard(), controller.getMatchUI().getLocalPlayers(),
                    Math.round(CARD_WIDTH * screenScale), Math.round(CARD_HEIGHT * screenScale)) {
                @Override
                public void onImageFetched() {
                    repaint();
                }
            };
            repaint();
        }

        /** Where this item's card art sits on screen, or null while it isn't showing. */
        Rectangle getCardBoundsOnScreen() {
            if (!isShowing() || getWidth() == 0) { return null; }
            final Point p = getLocationOnScreen();
            return new Rectangle(p.x, p.y + headerHeight(), getWidth(), getHeight() - headerHeight());
        }

        /** Where a targeting arc from this item starts, in screen coordinates. */
        public Point getArcOrigin() {
            try {
                final Point p = getLocationOnScreen();
                p.x += Math.round(getWidth() * CardPanel.TARGET_ORIGIN_FACTOR_X);
                p.y += headerHeight() + Math.round((getHeight() - headerHeight()) * CardPanel.TARGET_ORIGIN_FACTOR_Y);
                return p;
            } catch (final Exception e) {
                //suppress exception that can occur if stack hidden while over an item
                if (hoveredItem == this) {
                    hoveredItem = null; //reset this if this happens
                }
                return null;
            }
        }

        @Override
        public void paintComponent(final Graphics g) {
            super.paintComponent(g);
            if (cachedImage == null) {
                refreshImage();
            }

            final int cardWidth = getWidth();
            final int headerHeight = headerHeight();
            final int cardHeight = getHeight() - headerHeight;
            final Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setFont(FSkin.getFont().getBaseFont());

            final int cornerSize = Math.max(4, Math.round(cardWidth * CardPanel.ROUNDED_CORNER_SIZE));
            final Shape fullClip = g2d.getClip();

            //header naming whoever put the item on the stack; rounded on top, flat where it
            //meets the card. Items that share a name with the one above have neither.
            if (showHeaderText) {
                g2d.setColor(headerColor);
                g2d.clipRect(0, 0, cardWidth, headerHeight);
                g2d.fillRoundRect(0, 0, cardWidth, headerHeight + cornerSize, cornerSize, cornerSize);
                g2d.setClip(fullClip);
                g2d.setColor(FSkin.getHighContrastColor(headerColor));
                final FontMetrics headerMetrics = g2d.getFontMetrics();
                final String name = clip(headerText, headerMetrics, cardWidth - 8);
                g2d.drawString(name, Math.max(4, (cardWidth - headerMetrics.stringWidth(name)) / 2),
                        (headerHeight + headerMetrics.getAscent()) / 2 - 1);
            }

            //the card itself
            final BufferedImage img = cachedImage.getImage();
            g2d.setColor(Color.black);
            g2d.fillRoundRect(0, headerHeight, cardWidth, cardHeight, cornerSize, cornerSize);
            if (img != null) {
                g2d.clipRect(0, headerHeight, cardWidth, cardHeight);
                g2d.drawImage(img, 0, headerHeight, cardWidth, cardHeight, null);
                g2d.setClip(fullClip);
            }

            if (hoveredItem == this) {
                g2d.setColor(FSkin.getColor(FSkin.Colors.CLR_ACTIVE).getColor());
                g2d.drawRoundRect(0, 0, cardWidth - 1, getHeight() - 1, cornerSize, cornerSize);
            }

            g2d.dispose();
        }
    }

    /** One line of the plain-text view of the stack. */
    @SuppressWarnings("serial")
    private class StackTextRow extends SkinnedTextArea {
        private static final int PADDING = 3;
        /** Width of the drop shadow an expanded row casts over the rows it covers. */
        private static final int SHADOW = 6;

        private boolean highlighted;
        private boolean raised;

        StackTextRow(final StackItemPanel panel) {
            final StackItemView item = panel.getItem();
            final String txt = (item.isOptionalTrigger() && controller.getMatchUI().isLocalPlayer(item.getActivatingPlayer())
                    ? "(OPTIONAL) " : "") + item.getText();

            setText(txt);
            setOpaque(true);
            setFocusable(false);
            setEditable(false);
            setLineWrap(true);
            setFont(FSkin.getFont());
            setWrapStyleWord(true);
            setHighlighted(false);

            final CardStateView curState = item.getSourceCard().getCurrentState();
            final boolean isFaceDown = item.getSourceCard().isFaceDown();
            final DetailColors color = isFaceDown ? CardDetailUtil.DetailColors.FACE_DOWN : CardDetailUtil.getBorderColor(curState, true);
            setBackground(new Color(color.r, color.g, color.b));
            setForeground(FSkin.getHighContrastColor(getBackground()));

            //hovering the text does what hovering the card does, so either column opens the row
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(final MouseEvent e) {
                    onItemEntered(panel);
                }
                @Override
                public void mouseExited(final MouseEvent e) {
                    onItemExited(panel);
                }
            });
        }

        boolean isRaised() {
            return raised;
        }

        /** How tall the row has to be to show all of its text wrapped at the given width. */
        int wrappedHeight(final int width) {
            final Dimension current = getSize();
            setSize(width, Short.MAX_VALUE);
            final int height = getPreferredSize().height;
            setSize(current);
            return height;
        }

        /** Marks the row matching the card the mouse is over in the cascade. */
        void setHighlighted(final boolean highlighted0) {
            highlighted = highlighted0;
            updateBorder();
        }

        /** Opens the row out over the ones below it, lifted clear by a drop shadow. */
        void setRaised(final boolean raised0) {
            if (raised == raised0) { return; }
            raised = raised0;
            setOpaque(!raised0); //raised rows paint their own background, on top of the shadow
            updateBorder();
        }

        private void updateBorder() {
            if (raised) {
                //the shadow is what lifts a raised row clear, so it carries no outline
                setBorder(new EmptyBorder(PADDING, PADDING, PADDING + SHADOW, PADDING));
            } else if (highlighted) {
                setBorder(new FSkin.LineSkinBorder(FSkin.getColor(FSkin.Colors.CLR_ACTIVE), PADDING));
            } else {
                setBorder(new EmptyBorder(PADDING, PADDING, PADDING, PADDING));
            }
        }

        @Override
        protected void paintComponent(final Graphics g) {
            if (raised) {
                final int w = getWidth();
                final int h = getHeight() - SHADOW;
                final Graphics2D g2d = (Graphics2D) g.create();
                //translucent copies dropped a pixel at a time, so the shadow under the
                //bottom edge is darkest there and fades out; nothing along the sides
                g2d.setColor(SHADOW_TINT);
                for (int i = 1; i <= SHADOW; i++) {
                    g2d.fillRect(0, i, w, h);
                }
                g2d.setColor(getBackground());
                g2d.fillRect(0, 0, w, h);
                g2d.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** Truncates text with an ellipsis so it fits the given width. */
    private static String clip(final String text, final FontMetrics fm, final int width) {
        if (fm.stringWidth(text) <= width) { return text; }
        int len = text.length();
        while (len > 0 && fm.stringWidth(text.substring(0, len) + "…") > width) {
            len--;
        }
        return text.substring(0, len) + "…";
    }

    //========= Custom class handling

    private final class AbilityMenu extends JPopupMenu {
        private static final long serialVersionUID = 1548494191627807962L;
        private final JCheckBoxMenuItem jmiAutoYield;
        private final JCheckBoxMenuItem jmiAlwaysYes;
        private final JCheckBoxMenuItem jmiAlwaysNo;
        private final JMenuItem jmiYieldToStack;
        private final JMenuItem jmiYieldToEntireStack;
        private StackItemView item;

        private String yieldKey = "";
        private boolean abilityScope;

        public AbilityMenu(){
            jmiAutoYield = new JCheckBoxMenuItem(Localizer.getInstance().getMessage("cbpAutoYieldMode"));
            jmiAutoYield.addActionListener(arg0 -> {
                final boolean autoYield = controller.getMatchUI().getGameController().shouldAutoYield(yieldKey);
                controller.getMatchUI().getGameController().setShouldAutoYield(yieldKey, !autoYield, abilityScope);
                if (!autoYield && controller.getMatchUI().getGameView().peekStack() == item) {
                    //auto-pass priority if ability is on top of stack
                    controller.getMatchUI().getGameController().passPriority();
                }
            });
            add(jmiAutoYield);

            jmiAlwaysYes = new JCheckBoxMenuItem(Localizer.getInstance().getMessage("lblAlwaysYes"));
            jmiAlwaysYes.addActionListener(arg0 -> {
                if (yieldKey.isEmpty()) return;
                IGameController gc = controller.getMatchUI().getGameController();
                TriggerDecision next = gc.getTriggerDecision(yieldKey) == TriggerDecision.ACCEPT ? TriggerDecision.ASK : TriggerDecision.ACCEPT;
                gc.setTriggerDecision(yieldKey, next, abilityScope);
            });
            add(jmiAlwaysYes);

            jmiAlwaysNo = new JCheckBoxMenuItem(Localizer.getInstance().getMessage("lblAlwaysNo"));
            jmiAlwaysNo.addActionListener(arg0 -> {
                if (yieldKey.isEmpty()) return;
                IGameController gc = controller.getMatchUI().getGameController();
                TriggerDecision next = gc.getTriggerDecision(yieldKey) == TriggerDecision.DECLINE ? TriggerDecision.ASK : TriggerDecision.DECLINE;
                gc.setTriggerDecision(yieldKey, next, abilityScope);
            });
            add(jmiAlwaysNo);

            jmiYieldToStack = new JMenuItem(Localizer.getInstance().getMessage("lblYieldToStack"));
            jmiYieldToStack.addActionListener(arg0 -> {
                final PlayerView local = controller.getMatchUI().getCurrentPlayer();
                if (local == null) return;
                controller.getMatchUI().getGameController().sendYieldUpdate(new YieldUpdate.StackYield(local, true, true));
                controller.getMatchUI().getGameController().passPriority();
            });
            add(jmiYieldToStack);

            jmiYieldToEntireStack = new JMenuItem(Localizer.getInstance().getMessage("lblYieldToEntireStack"));
            jmiYieldToEntireStack.addActionListener(arg0 -> resolveEntireStack());
            add(jmiYieldToEntireStack);
        }

        public void setStackInstance(final StackItemView item0) {
            item = item0;
            yieldKey = item.getKey();
            abilityScope = controller.getMatchUI().getGameController().getYieldController().isAbilityScope();

            jmiAutoYield.setVisible(item.isAbility());
            jmiAutoYield.setSelected(item.isAbility()
                    && controller.getMatchUI().getGameController().shouldAutoYield(yieldKey));

            if (item.isOptionalTrigger() && controller.getMatchUI().isLocalPlayer(item.getActivatingPlayer()) && !yieldKey.isEmpty()) {
                TriggerDecision decision = controller.getMatchUI().getGameController().getTriggerDecision(yieldKey);
                jmiAlwaysYes.setSelected(decision == TriggerDecision.ACCEPT);
                jmiAlwaysNo.setSelected(decision == TriggerDecision.DECLINE);
                jmiAlwaysYes.setVisible(true);
                jmiAlwaysNo.setVisible(true);
            } else {
                jmiAlwaysYes.setVisible(false);
                jmiAlwaysNo.setVisible(false);
            }
        }
    }
}
