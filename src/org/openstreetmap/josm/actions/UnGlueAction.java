// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.actions;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.dialogs.PropertiesMembershipChoiceDialog;
import org.openstreetmap.josm.gui.dialogs.PropertiesMembershipChoiceDialog.ExistingBothNew;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.UserCancelException;
import org.openstreetmap.josm.tools.Utils;

/**
 * Duplicate nodes that are used by multiple ways.
 *
 * Resulting nodes are identical, up to their position.
 *
 * This is the opposite of the MergeNodesAction.
 *
 * If a single node is selected, it will copy that node and remove all tags from the old one
 */
public class UnGlueAction extends JosmAction {

    private transient Node selectedNode;
    private transient Way selectedWay;
    private transient Set<Node> selectedNodes;

    /**
     * Create a new UnGlueAction.
     */
    public UnGlueAction() {
        super(tr("UnGlue Ways"), "unglueways", tr("Duplicate nodes that are used by multiple ways."),
                Shortcut.registerShortcut("tools:unglue", tr("Tool: {0}", tr("UnGlue Ways")), KeyEvent.VK_G, Shortcut.DIRECT), true);
        setHelpId(ht("/Action/UnGlue"));
    }

    /**
     * Called when the action is executed.
     *
     * This method does some checking on the selection and calls the matching unGlueWay method.
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        try {
            unglue(e);
        } catch (UserCancelException ignore) {
            Logging.trace(ignore);
        } finally {
            cleanup();
        }
    }

    protected void unglue(ActionEvent e) throws UserCancelException {

        Collection<OsmPrimitive> selection = getLayerManager().getEditDataSet().getSelected();

        String errMsg = null;
        int errorTime = Notification.TIME_DEFAULT;
        if (checkSelectionOneNodeAtMostOneWay(selection)) {
            checkAndConfirmOutlyingUnglue();
            int count = 0;
            for (Way w : selectedNode.getParentWays()) {
                if (!w.isUsable() || w.getNodesCount() < 1) {
                    continue;
                }
                count++;
            }
            if (count < 2) {
                boolean selfCrossing = false;
                if (count == 1) {
                    // First try unglue self-crossing way
                    selfCrossing = unglueSelfCrossingWay();
                }
                // If there aren't enough ways, maybe the user wanted to unglue the nodes
                // (= copy tags to a new node)
                if (!selfCrossing)
                    if (checkForUnglueNode(selection)) {
                        unglueOneNodeAtMostOneWay(e);
                    } else {
                        errorTime = Notification.TIME_SHORT;
                        errMsg = tr("This node is not glued to anything else.");
                    }
            } else {
                // and then do the work.
                unglueWays();
            }
        } else if (checkSelectionOneWayAnyNodes(selection)) {
            checkAndConfirmOutlyingUnglue();
            Set<Node> tmpNodes = new HashSet<>();
            for (Node n : selectedNodes) {
                int count = 0;
                for (Way w : n.getParentWays()) {
                    if (!w.isUsable()) {
                        continue;
                    }
                    count++;
                }
                if (count >= 2) {
                    tmpNodes.add(n);
                }
            }
            if (tmpNodes.isEmpty()) {
                if (selection.size() > 1) {
                    errMsg = tr("None of these nodes are glued to anything else.");
                } else {
                    errMsg = tr("None of this way''s nodes are glued to anything else.");
                }
            } else {
                // and then do the work.
                selectedNodes = tmpNodes;
                unglueOneWayAnyNodes();
            }
        } else {
            errorTime = Notification.TIME_VERY_LONG;
            errMsg =
                tr("The current selection cannot be used for unglueing.")+'\n'+
                '\n'+
                tr("Select either:")+'\n'+
                tr("* One tagged node, or")+'\n'+
                tr("* One node that is used by more than one way, or")+'\n'+
                tr("* One node that is used by more than one way and one of those ways, or")+'\n'+
                tr("* One way that has one or more nodes that are used by more than one way, or")+'\n'+
                tr("* One way and one or more of its nodes that are used by more than one way.")+'\n'+
                '\n'+
                tr("Note: If a way is selected, this way will get fresh copies of the unglued\n"+
                        "nodes and the new nodes will be selected. Otherwise, all ways will get their\n"+
                "own copy and all nodes will be selected.");
        }

        if (errMsg != null) {
            new Notification(
                    errMsg)
                    .setIcon(JOptionPane.ERROR_MESSAGE)
                    .setDuration(errorTime)
                    .show();
        }
    }

    private void cleanup() {
        selectedNode = null;
        selectedWay = null;
        selectedNodes = null;
    }

    static void update(PropertiesMembershipChoiceDialog dialog, Node existingNode, List<Node> newNodes, Collection<Command> cmds) {
        updateMemberships(dialog.getMemberships().orElse(null), existingNode, newNodes, cmds);
        updateProperties(dialog.getTags().orElse(null), existingNode, newNodes, cmds);
    }

    private static void updateProperties(ExistingBothNew tags, Node existingNode, Iterable<Node> newNodes, Collection<Command> cmds) {
        if (ExistingBothNew.NEW == tags) {
            final Node newSelectedNode = new Node(existingNode);
            newSelectedNode.removeAll();
            cmds.add(new ChangeCommand(existingNode, newSelectedNode));
        } else if (ExistingBothNew.OLD == tags) {
            for (Node newNode : newNodes) {
                newNode.removeAll();
            }
        }
    }

    /**
     * Assumes there is one tagged Node stored in selectedNode that it will try to unglue.
     * (i.e. copy node and remove all tags from the old one. Relations will not be removed)
     * @param e event that triggered the action
     */
    private void unglueOneNodeAtMostOneWay(ActionEvent e) {
        final PropertiesMembershipChoiceDialog dialog;
        try {
            dialog = PropertiesMembershipChoiceDialog.showIfNecessary(Collections.singleton(selectedNode), true);
        } catch (UserCancelException ex) {
            Logging.trace(ex);
            return;
        }

        final Node unglued = new Node(selectedNode, true);
        boolean moveSelectedNode = false;

        List<Command> cmds = new LinkedList<>();
        cmds.add(new AddCommand(selectedNode.getDataSet(), unglued));
        if (dialog != null && ExistingBothNew.NEW == dialog.getTags().orElse(null)) {
            // unglued node gets the ID and history, thus replace way node with a fresh one
            final Way way = selectedNode.getParentWays().get(0);
            final List<Node> newWayNodes = way.getNodes();
            newWayNodes.replaceAll(n -> selectedNode.equals(n) ? unglued : n);
            cmds.add(new ChangeNodesCommand(way, newWayNodes));
            updateMemberships(dialog.getMemberships().map(ExistingBothNew::opposite).orElse(null),
                    selectedNode, Collections.singletonList(unglued), cmds);
            updateProperties(dialog.getTags().map(ExistingBothNew::opposite).orElse(null),
                    selectedNode, Collections.singletonList(unglued), cmds);
            moveSelectedNode = true;
        } else if (dialog != null) {
            update(dialog, selectedNode, Collections.singletonList(unglued), cmds);
        }

        // If this wasn't called from menu, place it where the cursor is/was
        MapView mv = MainApplication.getMap().mapView;
        if (e.getSource() instanceof JPanel) {
            final LatLon latLon = mv.getLatLon(mv.lastMEvent.getX(), mv.lastMEvent.getY());
            if (moveSelectedNode) {
                cmds.add(new MoveCommand(selectedNode, latLon));
            } else {
                unglued.setCoor(latLon);
            }
        }

        UndoRedoHandler.getInstance().add(new SequenceCommand(tr("Unglued Node"), cmds));
        getLayerManager().getEditDataSet().setSelected(moveSelectedNode ? selectedNode : unglued);
        mv.repaint();
    }

    /**
     * Checks if selection is suitable for ungluing. This is the case when there's a single,
     * tagged node selected that's part of at least one way (ungluing an unconnected node does
     * not make sense. Due to the call order in actionPerformed, this is only called when the
     * node is only part of one or less ways.
     *
     * @param selection The selection to check against
     * @return {@code true} if selection is suitable
     */
    private boolean checkForUnglueNode(Collection<? extends OsmPrimitive> selection) {
        if (selection.size() != 1)
            return false;
        OsmPrimitive n = (OsmPrimitive) selection.toArray()[0];
        if (!(n instanceof Node))
            return false;
        if (((Node) n).getParentWays().isEmpty())
            return false;

        selectedNode = (Node) n;
        return selectedNode.isTagged();
    }

    /**
     * Checks if the selection consists of something we can work with.
     * Checks only if the number and type of items selected looks good.
     *
     * If this method returns "true", selectedNode and selectedWay will be set.
     *
     * Returns true if either one node is selected or one node and one
     * way are selected and the node is part of the way.
     *
     * The way will be put into the object variable "selectedWay", the node into "selectedNode".
     * @param selection selected primitives
     * @return true if either one node is selected or one node and one way are selected and the node is part of the way
     */
    private boolean checkSelectionOneNodeAtMostOneWay(Collection<? extends OsmPrimitive> selection) {

        int size = selection.size();
        if (size < 1 || size > 2)
            return false;

        selectedNode = null;
        selectedWay = null;

        for (OsmPrimitive p : selection) {
            if (p instanceof Node) {
                selectedNode = (Node) p;
                if (size == 1 || selectedWay != null)
                    return size == 1 || selectedWay.containsNode(selectedNode);
            } else if (p instanceof Way) {
                selectedWay = (Way) p;
                if (size == 2 && selectedNode != null)
                    return selectedWay.containsNode(selectedNode);
            }
        }

        return false;
    }

    /**
     * Checks if the selection consists of something we can work with.
     * Checks only if the number and type of items selected looks good.
     *
     * Returns true if one way and any number of nodes that are part of that way are selected.
     * Note: "any" can be none, then all nodes of the way are used.
     *
     * The way will be put into the object variable "selectedWay", the nodes into "selectedNodes".
     * @param selection selected primitives
     * @return true if one way and any number of nodes that are part of that way are selected
     */
    private boolean checkSelectionOneWayAnyNodes(Collection<? extends OsmPrimitive> selection) {
        if (selection.isEmpty())
            return false;

        selectedWay = null;
        for (OsmPrimitive p : selection) {
            if (p instanceof Way) {
                if (selectedWay != null)
                    return false;
                selectedWay = (Way) p;
            }
        }
        if (selectedWay == null)
            return false;

        selectedNodes = new HashSet<>();
        for (OsmPrimitive p : selection) {
            if (p instanceof Node) {
                Node n = (Node) p;
                if (!selectedWay.containsNode(n))
                    return false;
                selectedNodes.add(n);
            }
        }

        if (selectedNodes.isEmpty()) {
            selectedNodes.addAll(selectedWay.getNodes());
        }

        return true;
    }

    /**
     * dupe the given node of the given way
     *
     * assume that originalNode is in the way
     * <ul>
     * <li>the new node will be put into the parameter newNodes.</li>
     * <li>the add-node command will be put into the parameter cmds.</li>
     * <li>the changed way will be returned and must be put into cmds by the caller!</li>
     * </ul>
     * @param originalNode original node to duplicate
     * @param w parent way
     * @param cmds List of commands that will contain the new "add node" command
     * @param newNodes List of nodes that will contain the new node
     * @return new way The modified way. Change command mus be handled by the caller
     */
    private static Way modifyWay(Node originalNode, Way w, List<Command> cmds, List<Node> newNodes) {
        // clone the node for the way
        Node newNode = new Node(originalNode, true /* clear OSM ID */);
        newNodes.add(newNode);
        cmds.add(new AddCommand(originalNode.getDataSet(), newNode));

        List<Node> nn = new ArrayList<>();
        for (Node pushNode : w.getNodes()) {
            if (originalNode == pushNode) {
                pushNode = newNode;
            }
            nn.add(pushNode);
        }
        Way newWay = new Way(w);
        newWay.setNodes(nn);

        return newWay;
    }

    /**
     * put all newNodes into the same relation(s) that originalNode is in
     * @param memberships where the memberships should be places
     * @param originalNode original node to duplicate
     * @param cmds List of commands that will contain the new "change relation" commands
     * @param newNodes List of nodes that contain the new node
     */
    private static void updateMemberships(ExistingBothNew memberships, Node originalNode, List<Node> newNodes, Collection<Command> cmds) {
        if (memberships == null || ExistingBothNew.OLD == memberships) {
            return;
        }
        // modify all relations containing the node
        for (Relation r : OsmPrimitive.getParentRelations(Collections.singleton(originalNode))) {
            if (r.isDeleted()) {
                continue;
            }
            Relation newRel = null;
            Map<String, Integer> rolesToReAdd = null; // <role name, index>
            int i = 0;
            for (RelationMember rm : r.getMembers()) {
                if (rm.isNode() && rm.getMember() == originalNode) {
                    if (newRel == null) {
                        newRel = new Relation(r);
                        rolesToReAdd = new HashMap<>();
                    }
                    if (rolesToReAdd != null) {
                        rolesToReAdd.put(rm.getRole(), i);
                    }
                }
                i++;
            }
            if (newRel != null) {
                if (rolesToReAdd != null) {
                    for (Map.Entry<String, Integer> role : rolesToReAdd.entrySet()) {
                        for (Node n : newNodes) {
                            newRel.addMember(role.getValue() + 1, new RelationMember(role.getKey(), n));
                        }
                        if (ExistingBothNew.NEW == memberships) {
                            // remove old member
                            newRel.removeMember(role.getValue());
                        }
                    }
                }
                cmds.add(new ChangeCommand(r, newRel));
            }
        }
    }

    /**
     * dupe a single node into as many nodes as there are ways using it, OR
     *
     * dupe a single node once, and put the copy on the selected way
     */
    private void unglueWays() {
        final PropertiesMembershipChoiceDialog dialog;
        try {
            dialog = PropertiesMembershipChoiceDialog.showIfNecessary(Collections.singleton(selectedNode), false);
        } catch (UserCancelException e) {
            Logging.trace(e);
            return;
        }

        List<Command> cmds = new LinkedList<>();
        List<Node> newNodes = new LinkedList<>();
        if (selectedWay == null) {
            Way wayWithSelectedNode = null;
            LinkedList<Way> parentWays = new LinkedList<>();
            for (OsmPrimitive osm : selectedNode.getReferrers()) {
                if (osm.isUsable() && osm instanceof Way) {
                    Way w = (Way) osm;
                    if (wayWithSelectedNode == null && !w.isFirstLastNode(selectedNode)) {
                        wayWithSelectedNode = w;
                    } else {
                        parentWays.add(w);
                    }
                }
            }
            if (wayWithSelectedNode == null) {
                parentWays.removeFirst();
            }
            for (Way w : parentWays) {
                cmds.add(new ChangeCommand(w, modifyWay(selectedNode, w, cmds, newNodes)));
            }
            notifyWayPartOfRelation(parentWays);
        } else {
            cmds.add(new ChangeCommand(selectedWay, modifyWay(selectedNode, selectedWay, cmds, newNodes)));
            notifyWayPartOfRelation(Collections.singleton(selectedWay));
        }

        if (dialog != null) {
            update(dialog, selectedNode, newNodes, cmds);
        }

        execCommands(cmds, newNodes);
    }

    /**
     * Add commands to undo-redo system.
     * @param cmds Commands to execute
     * @param newNodes New created nodes by this set of command
     */
    private void execCommands(List<Command> cmds, List<Node> newNodes) {
        UndoRedoHandler.getInstance().add(new SequenceCommand(/* for correct i18n of plural forms - see #9110 */
                trn("Dupe into {0} node", "Dupe into {0} nodes", newNodes.size() + 1L, newNodes.size() + 1L), cmds));
        // select one of the new nodes
        getLayerManager().getEditDataSet().setSelected(newNodes.get(0));
    }

    /**
     * Duplicates a node used several times by the same way. See #9896.
     * @return true if action is OK false if there is nothing to do
     */
    private boolean unglueSelfCrossingWay() {
        // According to previous check, only one valid way through that node
        Way way = null;
        for (Way w: selectedNode.getParentWays()) {
            if (w.isUsable() && w.getNodesCount() >= 1) {
                way = w;
            }
        }
        if (way == null) {
            return false;
        }
        List<Command> cmds = new LinkedList<>();
        List<Node> oldNodes = way.getNodes();
        List<Node> newNodes = new ArrayList<>(oldNodes.size());
        List<Node> addNodes = new ArrayList<>();
        boolean seen = false;
        for (Node n: oldNodes) {
            if (n == selectedNode) {
                if (seen) {
                    Node newNode = new Node(n, true /* clear OSM ID */);
                    cmds.add(new AddCommand(selectedNode.getDataSet(), newNode));
                    newNodes.add(newNode);
                    addNodes.add(newNode);
                } else {
                    newNodes.add(n);
                    seen = true;
                }
            } else {
                newNodes.add(n);
            }
        }
        if (addNodes.isEmpty()) {
            // selectedNode doesn't need unglue
            return false;
        }
        cmds.add(new ChangeNodesCommand(way, newNodes));
        notifyWayPartOfRelation(Collections.singleton(way));
        try {
            final PropertiesMembershipChoiceDialog dialog = PropertiesMembershipChoiceDialog.showIfNecessary(
                    Collections.singleton(selectedNode), false);
            if (dialog != null) {
                update(dialog, selectedNode, addNodes, cmds);
            }
            execCommands(cmds, addNodes);
            return true;
        } catch (UserCancelException ignore) {
            Logging.trace(ignore);
        }
        return false;
    }

    /**
     * dupe all nodes that are selected, and put the copies on the selected way
     *
     */
    private void unglueOneWayAnyNodes() {
        Way tmpWay = selectedWay;

        final PropertiesMembershipChoiceDialog dialog;
        try {
            dialog = PropertiesMembershipChoiceDialog.showIfNecessary(selectedNodes, false);
        } catch (UserCancelException e) {
            Logging.trace(e);
            return;
        }

        List<Command> cmds = new LinkedList<>();
        List<Node> allNewNodes = new LinkedList<>();
        for (Node n : selectedNodes) {
            List<Node> newNodes = new LinkedList<>();
            tmpWay = modifyWay(n, tmpWay, cmds, newNodes);
            if (dialog != null) {
                update(dialog, n, newNodes, cmds);
            }
            allNewNodes.addAll(newNodes);
        }
        cmds.add(new ChangeCommand(selectedWay, tmpWay)); // only one changeCommand for a way, else garbage will happen
        notifyWayPartOfRelation(Collections.singleton(selectedWay));

        UndoRedoHandler.getInstance().add(new SequenceCommand(
                trn("Dupe {0} node into {1} nodes", "Dupe {0} nodes into {1} nodes",
                        selectedNodes.size(), selectedNodes.size(), selectedNodes.size()+allNewNodes.size()), cmds));
        getLayerManager().getEditDataSet().setSelected(allNewNodes);
    }

    @Override
    protected void updateEnabledState() {
        updateEnabledStateOnCurrentSelection();
    }

    @Override
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
        updateEnabledStateOnModifiableSelection(selection);
    }

    protected void checkAndConfirmOutlyingUnglue() throws UserCancelException {
        List<OsmPrimitive> primitives = new ArrayList<>(2 + (selectedNodes == null ? 0 : selectedNodes.size()));
        if (selectedNodes != null)
            primitives.addAll(selectedNodes);
        if (selectedNode != null)
            primitives.add(selectedNode);
        final boolean ok = checkAndConfirmOutlyingOperation("unglue",
                tr("Unglue confirmation"),
                tr("You are about to unglue nodes outside of the area you have downloaded."
                        + "<br>"
                        + "This can cause problems because other objects (that you do not see) might use them."
                        + "<br>"
                        + "Do you really want to unglue?"),
                tr("You are about to unglue incomplete objects."
                        + "<br>"
                        + "This will cause problems because you don''t see the real object."
                        + "<br>" + "Do you really want to unglue?"),
                primitives, null);
        if (!ok) {
            throw new UserCancelException();
        }
    }

    protected void notifyWayPartOfRelation(final Iterable<Way> ways) {
        final Set<String> affectedRelations = new HashSet<>();
        for (Way way : ways) {
            for (OsmPrimitive ref : way.getReferrers()) {
                if (ref instanceof Relation && ref.isUsable()) {
                    affectedRelations.add(ref.getDisplayName(DefaultNameFormatter.getInstance()));
                }
            }
        }
        if (affectedRelations.isEmpty()) {
            return;
        }

        final String msg1 = trn("Unglueing affected {0} relation: {1}", "Unglueing affected {0} relations: {1}",
                affectedRelations.size(), affectedRelations.size(), Utils.joinAsHtmlUnorderedList(affectedRelations));
        final String msg2 = trn("Ensure that the relation has not been broken!", "Ensure that the relations have not been broken!",
                affectedRelations.size());
        new Notification("<html>" + msg1 + msg2).setIcon(JOptionPane.WARNING_MESSAGE).show();
    }
}
