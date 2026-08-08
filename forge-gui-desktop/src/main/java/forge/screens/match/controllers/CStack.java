package forge.screens.match.controllers;

import java.util.List;

import forge.game.GameView;
import forge.gui.framework.DragCell;
import forge.gui.framework.EDocID;
import forge.gui.framework.ICDoc;
import forge.gui.framework.IVDoc;
import forge.gui.framework.SDisplayUtil;
import forge.gui.framework.SRearrangingUtil;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.screens.match.views.FloatingStack;
import forge.screens.match.views.VStack;
import forge.view.FView;

/**
 * Controls the combat panel in the match UI.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 *
 */
public class CStack implements ICDoc {

    private final CMatchUI matchUI;
    private final VStack view;

    private FloatingStack floatingStack;

    public CStack(final CMatchUI matchUI) {
        this.matchUI = matchUI;
        this.view = new VStack(this);
    }

    public final CMatchUI getMatchUI() {
        return matchUI;
    }
    public final VStack getView() {
        return view;
    }

    /** Lazily creates the floating stack window and moves the cascade into it. */
    private FloatingStack getFloatingStack() {
        if (floatingStack == null) {
            floatingStack = new FloatingStack();
            floatingStack.getTitleBar().addRightControl(view.getTextToggle());
            view.populateInto(floatingStack.getContentPanel());
        }
        return floatingStack;
    }

    /**
     * Called when the stack view itself changes shape — opening or closing the
     * text list, the only thing that changes the window's size.
     */
    public void stackLayoutChanged() {
        if (floatingStack == null) { return; }
        floatingStack.sizeToContent();
    }

    /**
     * Puts the stack where {@link ForgePreferences.FPref#UI_FLOATING_STACK} says it
     * belongs. Called after the saved layout has been loaded, which re-docks every
     * registered doc regardless of which mode is in force.
     */
    public void applyDockMode() {
        if (isFloatingEnabled()) {
            undockStack();
        } else {
            dockStack();
        }
    }

    /**
     * Pulls the stack out of whatever dock cell the saved layout put it in, so
     * it exists only as the floating window. Collapses the cell if the stack
     * was the only thing in it, mirroring {@link CPrompt}'s undocking.
     */
    private void undockStack() {
        final DragCell cell = view.getParentCell();
        if (cell != null) {
            cell.removeDoc(view);
            view.setParentCell(null);
            if (cell.getDocs().isEmpty() && SRearrangingUtil.fillGapIfPossible(cell)) {
                FView.SINGLETON_INSTANCE.removeDragCell(cell);
            }
        }
        // Always re-populate: docking calls VStack.populate(), which reparents
        // the cascade into the cell body.
        view.populateInto(getFloatingStack().getContentPanel());
    }

    /**
     * Makes sure the stack has a cell to live in. The saved layout normally
     * supplies one, but a layout written while the stack was floating has no
     * entry for it at all — so fall back to a cell that does exist, rather than
     * leaving the match with nowhere to watch the stack.
     */
    private void dockStack() {
        closeFloatingStack();
        if (view.getParentCell() != null) {
            return;
        }
        final IVDoc<? extends ICDoc> anchor = EDocID.REPORT_LOG.getDoc();
        DragCell target = anchor == null ? null : anchor.getParentCell();
        if (target == null) {
            final List<DragCell> cells = FView.SINGLETON_INSTANCE.getDragCells();
            if (cells.isEmpty()) { return; }
            target = cells.get(0);
        }
        target.addDoc(view);
        target.setSelected(view);
    }

    /** Whether the stack shows as a floating window rather than a docked panel. */
    private boolean isFloatingEnabled() {
        return FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_FLOATING_STACK);
    }

    /** Tears the floating stack down at the end of a match. */
    public void closeFloatingStack() {
        if (floatingStack != null) {
            floatingStack.setVisible(false);
            floatingStack.dispose();
            floatingStack = null;
        }
    }

    @Override
    public void register() {
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.ICDoc#initialize()
     */
    @Override
    public void initialize() {
    }

    @Override
    public void update() {
        SDisplayUtil.showTab(EDocID.REPORT_STACK.getDoc()); //no-op once undocked

        if (!isFloatingEnabled()) {
            //a docked stack is always on screen; it just empties out when nothing is waiting
            view.updateStack();
            view.animateArrivals();
            return;
        }

        final GameView game = matchUI.getGameView();
        if (game == null || game.getStack().isEmpty()) {
            view.updateStack();
            if (floatingStack != null) {
                floatingStack.setVisible(false);
            }
            return;
        }
        final FloatingStack window = getFloatingStack();
        view.updateStack();
        window.sizeToContent();
        window.setVisible(true);
        window.toFront();
        // Only now are the new items where they will end up, so only now can
        // anything be flown into them.
        view.animateArrivals();
    }
}
