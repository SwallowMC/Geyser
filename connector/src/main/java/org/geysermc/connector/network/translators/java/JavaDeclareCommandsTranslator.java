/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.translators.java;

import com.github.steveice10.mc.protocol.data.game.command.CommandNode;
import com.github.steveice10.mc.protocol.data.game.command.CommandParser;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerDeclareCommandsPacket;
import com.nukkitx.protocol.bedrock.data.command.CommandData;
import com.nukkitx.protocol.bedrock.data.command.CommandEnumData;
import com.nukkitx.protocol.bedrock.data.command.CommandParamData;
import com.nukkitx.protocol.bedrock.data.command.CommandParamType;
import com.nukkitx.protocol.bedrock.packet.AvailableCommandsPacket;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import lombok.Getter;
import net.kyori.adventure.text.format.NamedTextColor;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.entity.type.EntityType;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.PacketTranslator;
import org.geysermc.connector.network.translators.Translator;
import org.geysermc.connector.network.translators.item.Enchantment;
import org.geysermc.connector.network.translators.item.ItemRegistry;
import org.geysermc.connector.network.translators.world.block.BlockTranslator;

import java.util.*;

@Translator(packet = ServerDeclareCommandsPacket.class)
public class JavaDeclareCommandsTranslator extends PacketTranslator<ServerDeclareCommandsPacket> {

    private static final String[] ENUM_BOOLEAN = {"true", "false"};

    private static final Hash.Strategy<CommandParamData[][]> PARAM_STRATEGY = new Hash.Strategy<CommandParamData[][]>() {
        @Override
        public int hashCode(CommandParamData[][] o) {
            return Arrays.deepHashCode(o);
        }

        @Override
        public boolean equals(CommandParamData[][] a, CommandParamData[][] b) {
            if (a == b) return true;
            if (a == null || b == null) return false;
            if (a.length != b.length) return false;
            for (int i = 0; i < a.length; i++) {
                CommandParamData[] a1 = a[i];
                CommandParamData[] b1 = b[i];
                if (a1.length != b1.length) return false;

                for (int j = 0; j < a1.length; j++) {
                    if (!a1[j].equals(b1[j])) return false;
                }
            }
            return true;
        }
    };

    @Override
    public void translate(ServerDeclareCommandsPacket packet, GeyserSession session) {
        // Don't send command suggestions if they are disabled
        if (!session.getConnector().getConfig().isCommandSuggestions()) {
            session.getConnector().getLogger().debug("Not sending translated command suggestions as they are disabled.");

            // Send an empty packet so Bedrock doesn't override /help with its own, built-in help command.
            AvailableCommandsPacket emptyPacket = new AvailableCommandsPacket();
            session.sendUpstreamPacket(emptyPacket);
            return;
        }

        CommandNode[] nodes = packet.getNodes();
        List<CommandData> commandData = new ArrayList<>();
        IntSet commandNodes = new IntOpenHashSet();
        Set<String> knownAliases = new HashSet<>();
        Map<CommandParamData[][], Set<String>> commands = new Object2ObjectOpenCustomHashMap<>(PARAM_STRATEGY);
        Int2ObjectMap<List<CommandNode>> commandArgs = new Int2ObjectOpenHashMap<>();

        // Get the first node, it should be a root node
        CommandNode rootNode = nodes[packet.getFirstNodeIndex()];

        // Loop through the root nodes to get all commands
        for (int nodeIndex : rootNode.getChildIndices()) {
            CommandNode node = nodes[nodeIndex];

            // Make sure we don't have duplicated commands (happens if there is more than 1 root node)
            if (!commandNodes.add(nodeIndex) || !knownAliases.add(node.getName().toLowerCase())) continue;

            // Get and update the commandArgs list with the found arguments
            if (node.getChildIndices().length >= 1) {
                for (int childIndex : node.getChildIndices()) {
                    commandArgs.computeIfAbsent(nodeIndex, ArrayList::new).add(nodes[childIndex]);
                }
            }

            // Get and parse all params
            CommandParamData[][] params = getParams(nodes[nodeIndex], nodes);

            // Insert the alias name into the command list
            commands.computeIfAbsent(params, index -> new HashSet<>()).add(node.getName().toLowerCase());
        }

        // The command flags, not sure what these do apart from break things
        List<CommandData.Flag> flags = Collections.emptyList();

        // Loop through all the found commands

        for (Map.Entry<CommandParamData[][], Set<String>> entry : commands.entrySet()) {
            String commandName = entry.getValue().iterator().next(); // We know this has a value

            // Create a basic alias
            CommandEnumData aliases = new CommandEnumData(commandName + "Aliases", entry.getValue().toArray(new String[0]), false);

            // Build the completed command and add it to the final list
            CommandData data = new CommandData(commandName, session.getConnector().getCommandManager().getDescription(commandName), flags, (byte) 0, aliases, entry.getKey());
            commandData.add(data);
        }

        // Add our commands to the AvailableCommandsPacket for the bedrock client
        AvailableCommandsPacket availableCommandsPacket = new AvailableCommandsPacket();
        availableCommandsPacket.getCommands().addAll(commandData);

        session.getConnector().getLogger().debug("Sending command packet of " + commandData.size() + " commands");

        // Finally, send the commands to the client
        session.sendUpstreamPacket(availableCommandsPacket);
    }

    /**
     * Build the command parameter array for the given command
     *
     * @param commandNode The command to build the parameters for
     * @param allNodes    Every command node
     * @return An array of parameter option arrays
     */
    private static CommandParamData[][] getParams(CommandNode commandNode, CommandNode[] allNodes) {
        // Check if the command is an alias and redirect it
        if (commandNode.getRedirectIndex() != -1) {
            GeyserConnector.getInstance().getLogger().debug("Redirecting command " + commandNode.getName() + " to " + allNodes[commandNode.getRedirectIndex()].getName());
            commandNode = allNodes[commandNode.getRedirectIndex()];
        }

        if (commandNode.getChildIndices().length >= 1) {
            // Create the root param node and build all the children
            ParamInfo rootParam = new ParamInfo(commandNode, null);
            rootParam.buildChildren(allNodes);

            List<CommandParamData[]> treeData = rootParam.getTree();

            return treeData.toArray(new CommandParamData[0][]);
        }

        return new CommandParamData[0][0];
    }

    /**
     * Convert Java edition command types to Bedrock edition
     *
     * @param parser Command type to convert
     * @return Bedrock parameter data type
     */
    private static Object mapCommandType(CommandParser parser) {
        if (parser == null) {
            return CommandParamType.STRING;
        }

        switch (parser) {
            case FLOAT:
            case ROTATION:
            case DOUBLE:
                return CommandParamType.FLOAT;

            case INTEGER:
                return CommandParamType.INT;

            case ENTITY:
            case GAME_PROFILE:
                return CommandParamType.TARGET;

            case BLOCK_POS:
                return CommandParamType.BLOCK_POSITION;

            case COLUMN_POS:
            case VEC3:
                return CommandParamType.POSITION;

            case MESSAGE:
                return CommandParamType.MESSAGE;

            case NBT:
            case NBT_COMPOUND_TAG:
            case NBT_TAG:
            case NBT_PATH:
                return CommandParamType.JSON;

            case RESOURCE_LOCATION:
            case FUNCTION:
                return CommandParamType.FILE_PATH;

            //case INT_RANGE:
            //    return CommandParamType.INT_RANGE; - no longer registered

            case BOOL:
                return ENUM_BOOLEAN;

            case OPERATION: // Possibly OPERATOR
                return CommandParamType.OPERATOR;

            case BLOCK_STATE:
                return BlockTranslator.getAllBlockIdentifiers();

            case ITEM_STACK:
                return ItemRegistry.ITEM_NAMES;

            case ITEM_ENCHANTMENT:
                return Enchantment.ALL_JAVA_IDENTIFIERS; //TODO: inventory branch use Java enums

            case ENTITY_SUMMON:
                return EntityType.ALL_JAVA_IDENTIFIERS;

            case COLOR:
                return NamedTextColor.NAMES.keys().toArray(new String[0]);

            case STRING:
            case VEC2:
            case BLOCK_PREDICATE:
            case ITEM_PREDICATE:
            case COMPONENT:
            case OBJECTIVE:
            case OBJECTIVE_CRITERIA:
            case PARTICLE:
            case SCOREBOARD_SLOT:
            case SCORE_HOLDER:
            case SWIZZLE:
            case TEAM:
            case ITEM_SLOT:
            case MOB_EFFECT:
            case ENTITY_ANCHOR:
            case RANGE:
            case FLOAT_RANGE:
            case DIMENSION:
            case TIME:
            default:
                return CommandParamType.STRING;
        }
    }

    @Getter
    private static class ParamInfo {
        private final CommandNode paramNode;
        private final CommandParamData paramData;
        private final List<List<ParamInfo>> children;

        /**
         * Create a new parameter info object
         *
         * @param paramNode CommandNode the parameter is for
         * @param paramData The existing parameters for the command
         */
        public ParamInfo(CommandNode paramNode, CommandParamData paramData) {
            this.paramNode = paramNode;
            this.paramData = paramData;
            this.children = new ArrayList<>();
        }

        /**
         * Build the array of all the child parameters (recursive)
         *
         * @param allNodes Every command node
         */
        public void buildChildren(CommandNode[] allNodes) {
            int enumIndex = -1;

            for (int paramID : paramNode.getChildIndices()) {
                CommandNode paramNode = allNodes[paramID];

                if (paramNode.getParser() == null) {
                    if (enumIndex == -1) {
                        enumIndex = children.size();

                        // Create the new enum command
                        createNewParamInfo(paramNode);
                    } else {
                        boolean foundCompatible = false;
                        for (List<ParamInfo> enumParamInfos : children) {
                            for (int i = 0; i < enumParamInfos.size(); i++) {
                                ParamInfo enumParamInfo = enumParamInfos.get(i);
                                // Check to make sure all descending nodes of this command are compatible - otherwise, create a new overload
                                if (isCompatible(allNodes, enumParamInfo.getParamNode(), paramNode)) {
                                    foundCompatible = true;
                                    // Extend the current list of enum values
                                    String[] enumOptions = Arrays.copyOf(enumParamInfo.getParamData().getEnumData().getValues(), enumParamInfo.getParamData().getEnumData().getValues().length + 1);
                                    enumOptions[enumOptions.length - 1] = paramNode.getName();

                                    // Re-create the command using the updated values
                                    CommandEnumData enumData = new CommandEnumData(enumParamInfo.getParamData().getEnumData().getName(), enumOptions, false);
                                    enumParamInfos.set(i, new ParamInfo(enumParamInfo.getParamNode(), new CommandParamData(enumParamInfo.getParamData().getName(), false, enumData, enumParamInfo.getParamData().getType(), null, Collections.emptyList())));
                                    break;
                                }
                            }
                        }
                        if (!foundCompatible) {
                            // Create a new subcommand with this exact type
                            createNewParamInfo(paramNode);
                        }
                    }
                } else {
                    // Put the non-enum param into the list
                    Object mappedType = mapCommandType(paramNode.getParser());
                    CommandEnumData enumData = null;
                    CommandParamType type = null;
                    if (mappedType instanceof String[]) {
                        enumData = new CommandEnumData(paramNode.getName(), (String[]) mappedType, false);
                    } else {
                        type = (CommandParamType) mappedType;
                    }
                    List<ParamInfo> infos = new ArrayList<>();
                    infos.add(new ParamInfo(paramNode, new CommandParamData(paramNode.getName(), false, enumData, type, null, Collections.emptyList())));
                    children.add(infos);
                }
            }

            // Recursively build all child options
            for (List<ParamInfo> children : children) {
                for (ParamInfo child : children) {
                    child.buildChildren(allNodes);
                }
            }
        }

        private void createNewParamInfo(CommandNode paramNode) {
            CommandEnumData enumData = new CommandEnumData(paramNode.getName(), new String[]{paramNode.getName()}, false);
            CommandParamType type = CommandParamType.STRING;

            List<ParamInfo> infos = new ArrayList<>();
            infos.add(new ParamInfo(paramNode, new CommandParamData(paramNode.getName(), false, enumData, type, null, Collections.emptyList())));
            children.add(infos);
        }

        /**
         * Comparing CommandNode type a and b, determine if they are in the same overload.
         * <p>
         * Take the <code>gamerule</code> command, and let's present three "subcommands" you can perform:
         *
         * <ul>
         *     <li><code>gamerule doDaylightCycle true</code></li>
         *     <li><code>gamerule announceAdvancements false</code></li>
         *     <li><code>gamerule randomTickSpeed 3</code></li>
         * </ul>
         *
         * While all three of them are indeed part of the same command, the command setting randomTickSpeed parses an int,
         * while the others use boolean. In Bedrock, this should be presented as a separate overload to indicate that this
         * does something a little different.
         * <p>
         * Therefore, this function will return <code>true</code> if the first two are compared, as they use the same
         * parsers. If the third is compared with either of the others, this function will return <code>false</code>.
         * <p>
         * Here's an example of how the above would be presented to Bedrock (as of 1.16.200). Notice how the top two <code>CommandParamData</code>
         * classes of each array are identical in type, but the following class is different:
         * <pre>
         *     overloads=[
         *         [
         *            CommandParamData(name=doDaylightCycle, optional=false, enumData=CommandEnumData(name=announceAdvancements, values=[announceAdvancements, doDaylightCycle], isSoft=false), type=STRING, postfix=null, options=[])
         *            CommandParamData(name=value, optional=false, enumData=CommandEnumData(name=value, values=[true, false], isSoft=false), type=null, postfix=null, options=[])
         *         ]
         *         [
         *            CommandParamData(name=randomTickSpeed, optional=false, enumData=CommandEnumData(name=randomTickSpeed, values=[randomTickSpeed], isSoft=false), type=STRING, postfix=null, options=[])
         *            CommandParamData(name=value, optional=false, enumData=null, type=INT, postfix=null, options=[])
         *         ]
         *     ]
         * </pre>
         *
         * @return if these two can be merged into one overload.
         */
        private boolean isCompatible(CommandNode[] allNodes, CommandNode a, CommandNode b) {
            if (a == b) return true;
            if (a.getParser() != b.getParser()) return false;
            if (a.getChildIndices().length != b.getChildIndices().length) return false;

            for (int i = 0; i < a.getChildIndices().length; i++) {
                boolean hasSimilarity = false;
                CommandNode a1 = allNodes[a.getChildIndices()[i]];
                // Search "b" until we find a child that matches this one
                for (int j = 0; j < b.getChildIndices().length; j++) {
                    if (isCompatible(allNodes, a1, allNodes[b.getChildIndices()[j]])) {
                        hasSimilarity = true;
                        break;
                    }
                }
                if (!hasSimilarity) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Get the tree of every parameter node (recursive)
         *
         * @return List of parameter options arrays for the command
         */
        public List<CommandParamData[]> getTree() {
            List<CommandParamData[]> treeParamData = new ArrayList<>();

            for (List<ParamInfo> children : children) {
                for (ParamInfo child : children) {
                    // Get the tree from the child
                    List<CommandParamData[]> childTree = child.getTree();

                    // Un-pack the tree append the child node to it and push into the list
                    for (CommandParamData[] subChild : childTree) {
                        CommandParamData[] tmpTree = new ArrayList<CommandParamData>() {
                            {
                                add(child.getParamData());
                                addAll(Arrays.asList(subChild));
                            }
                        }.toArray(new CommandParamData[0]);

                        treeParamData.add(tmpTree);
                    }

                    // If we have no more child parameters just the child
                    if (childTree.size() == 0) {
                        treeParamData.add(new CommandParamData[] { child.getParamData() });
                    }
                }
            }

            return treeParamData;
        }

        @Override
        public String toString() {
            return "ParamInfo{" +
                    "paramNode=" + paramNode +
                    ", paramData=" + paramData +
                    ", children=" + children +
                    '}';
        }
    }
}
