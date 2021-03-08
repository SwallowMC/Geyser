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

package org.geysermc.connector.network.translators.item;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.steveice10.mc.protocol.data.game.entity.metadata.ItemStack;
import com.nukkitx.nbt.*;
import com.nukkitx.protocol.bedrock.data.inventory.ComponentItemData;
import com.nukkitx.protocol.bedrock.data.inventory.ItemData;
import com.nukkitx.protocol.bedrock.packet.StartGamePacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.utils.FileUtils;
import org.geysermc.connector.utils.LanguageUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Registry for anything item related.
 */
public class ItemRegistry {

    private static final Map<String, ItemEntry> JAVA_IDENTIFIER_MAP = new HashMap<>();

    /**
     * A list of all identifiers that only exist on Java. Used to prevent creative items from becoming these unintentionally.
     */
    private static final List<String> JAVA_ONLY_ITEMS = Arrays.asList("minecraft:spectral_arrow", "minecraft:debug_stick",
            "minecraft:knowledge_book", "minecraft:tipped_arrow", "minecraft:furnace_minecart");

    public static final ItemData[] CREATIVE_ITEMS;

    public static final List<StartGamePacket.ItemEntry> ITEMS = new ArrayList<>();
    public static final Int2ObjectMap<ItemEntry> ITEM_ENTRIES = new Int2ObjectOpenHashMap<>();

    /**
     * A list of all Java item names.
     */
    public static final String[] ITEM_NAMES;

    /**
     * Bamboo item entry, used in PandaEntity.java
     */
    public static ItemEntry BAMBOO;
    /**
     * Boat item entries, used in BedrockInventoryTransactionTranslator.java
     */
    public static IntList BOATS = new IntArrayList();
    /**
     * Bucket item entries (excluding the milk bucket), used in BedrockInventoryTransactionTranslator.java
     */
    public static IntList BUCKETS = new IntArrayList();
    /**
     * Empty item bucket, used in BedrockInventoryTransactionTranslator.java
     */
    public static ItemEntry MILK_BUCKET;
    /**
     * Egg item entry, used in JavaEntityStatusTranslator.java
     */
    public static ItemEntry EGG;
    /**
     * Gold item entry, used in PiglinEntity.java
     */
    public static ItemEntry GOLD;
    /**
     * Shield item entry, used in Entity.java and LivingEntity.java
     */
    public static ItemEntry SHIELD;
    /**
     * Wheat item entry, used in AbstractHorseEntity.java
     */
    public static ItemEntry WHEAT;
    /**
     * Writable book item entry, used in BedrockBookEditTranslator.java
     */
    public static ItemEntry WRITABLE_BOOK;

    public static int BARRIER_INDEX = 0;

    /**
     * Stores the properties and data of the "custom" furnace minecart item.
     */
    public static final ComponentItemData FURNACE_MINECART_DATA;

    public static void init() {
        // no-op
    }

    static {
        /* Load item palette */
        InputStream stream = FileUtils.getResource("bedrock/runtime_item_states.json");

        TypeReference<List<JsonNode>> itemEntriesType = new TypeReference<List<JsonNode>>() {
        };

        // Used to get the Bedrock namespaced ID (in instances where there are small differences)
        Int2ObjectMap<String> bedrockIdToIdentifier = new Int2ObjectOpenHashMap<>();

        List<String> itemNames = new ArrayList<>();

        List<JsonNode> itemEntries;
        try {
            itemEntries = GeyserConnector.JSON_MAPPER.readValue(stream, itemEntriesType);
        } catch (Exception e) {
            throw new AssertionError(LanguageUtils.getLocaleStringLog("geyser.toolbox.fail.runtime_bedrock"), e);
        }

        int lodestoneCompassId = 0;

        for (JsonNode entry : itemEntries) {
            ITEMS.add(new StartGamePacket.ItemEntry(entry.get("name").textValue(), (short) entry.get("id").intValue()));
            bedrockIdToIdentifier.put(entry.get("id").intValue(), entry.get("name").textValue());
            if (entry.get("name").textValue().equals("minecraft:lodestone_compass")) {
                lodestoneCompassId = entry.get("id").intValue();
            }
        }

        stream = FileUtils.getResource("mappings/items.json");

        JsonNode items;
        try {
            items = GeyserConnector.JSON_MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new AssertionError(LanguageUtils.getLocaleStringLog("geyser.toolbox.fail.runtime_java"), e);
        }

        int itemIndex = 0;
        int javaFurnaceMinecartId = 0;
        boolean usingFurnaceMinecart = GeyserConnector.getInstance().getConfig().isAddNonBedrockItems();
        Iterator<Map.Entry<String, JsonNode>> iterator = items.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (usingFurnaceMinecart && entry.getKey().equals("minecraft:furnace_minecart")) {
                javaFurnaceMinecartId = itemIndex;
                itemIndex++;
                continue;
            }
            int bedrockId = entry.getValue().get("bedrock_id").intValue();
            String bedrockIdentifier = bedrockIdToIdentifier.get(bedrockId);
            if (bedrockIdentifier == null) {
                throw new RuntimeException("Missing Bedrock ID in mappings!: " + bedrockId);
            }
            JsonNode stackSizeNode = entry.getValue().get("stack_size");
            int stackSize = stackSizeNode == null ? 64 : stackSizeNode.intValue();
            if (entry.getValue().has("tool_type")) {
                if (entry.getValue().has("tool_tier")) {
                    ITEM_ENTRIES.put(itemIndex, new ToolItemEntry(
                            entry.getKey(), bedrockIdentifier, itemIndex, bedrockId,
                            entry.getValue().get("bedrock_data").intValue(),
                            entry.getValue().get("tool_type").textValue(),
                            entry.getValue().get("tool_tier").textValue(),
                            entry.getValue().get("is_block").booleanValue(),
                            stackSize));
                } else {
                    ITEM_ENTRIES.put(itemIndex, new ToolItemEntry(
                            entry.getKey(), bedrockIdentifier, itemIndex, bedrockId,
                            entry.getValue().get("bedrock_data").intValue(),
                            entry.getValue().get("tool_type").textValue(),
                            "", entry.getValue().get("is_block").booleanValue(),
                            stackSize));
                }
            } else {
                ITEM_ENTRIES.put(itemIndex, new ItemEntry(
                        entry.getKey(), bedrockIdentifier, itemIndex, bedrockId,
                        entry.getValue().get("bedrock_data").intValue(),
                        entry.getValue().get("is_block").booleanValue(),
                        stackSize));
            }
            switch (entry.getKey()) {
                case "minecraft:barrier":
                    BARRIER_INDEX = itemIndex;
                    break;
                case "minecraft:bamboo":
                    BAMBOO = ITEM_ENTRIES.get(itemIndex);
                    break;
                case "minecraft:egg":
                    EGG = ITEM_ENTRIES.get(itemIndex);
                    break;
                case "minecraft:gold_ingot":
                    GOLD = ITEM_ENTRIES.get(itemIndex);
                    break;
                case "minecraft:shield":
                    SHIELD = ITEM_ENTRIES.get(itemIndex);
                    break;
                case "minecraft:milk_bucket":
                    MILK_BUCKET = ITEM_ENTRIES.get(itemIndex);
                    break;
                case "minecraft:wheat":
                    WHEAT = ITEM_ENTRIES.get(itemIndex);
                    break;
                case "minecraft:writable_book":
                    WRITABLE_BOOK = ITEM_ENTRIES.get(itemIndex);
                    break;
                default:
                    break;
            }

            if (entry.getKey().contains("boat")) {
                BOATS.add(entry.getValue().get("bedrock_id").intValue());
            } else if (entry.getKey().contains("bucket") && !entry.getKey().contains("milk")) {
                BUCKETS.add(entry.getValue().get("bedrock_id").intValue());
            }

            itemNames.add(entry.getKey());

            itemIndex++;
        }

        itemNames.add("minecraft:furnace_minecart");
        itemNames.add("minecraft:spectral_arrow");

        if (lodestoneCompassId == 0) {
            throw new RuntimeException("Lodestone compass not found in item palette!");
        }

        // Add the loadstone compass since it doesn't exist on java but we need it for item conversion
        ITEM_ENTRIES.put(itemIndex, new ItemEntry("minecraft:lodestone_compass", "minecraft:lodestone_compass", itemIndex,
                lodestoneCompassId, 0, false, 1));

        /* Load creative items */
        stream = FileUtils.getResource("bedrock/creative_items.json");

        JsonNode creativeItemEntries;
        try {
            creativeItemEntries = GeyserConnector.JSON_MAPPER.readTree(stream).get("items");
        } catch (Exception e) {
            throw new AssertionError(LanguageUtils.getLocaleStringLog("geyser.toolbox.fail.creative"), e);
        }

        int netId = 1;
        List<ItemData> creativeItems = new ArrayList<>();
        for (JsonNode itemNode : creativeItemEntries) {
            ItemData item = getBedrockItemFromJson(itemNode);
            creativeItems.add(ItemData.fromNet(netId++, item.getId(), item.getDamage(), item.getCount(), item.getTag()));
        }

        if (usingFurnaceMinecart) {
            // Add the furnace minecart as an item
            int furnaceMinecartId = ITEMS.size() + 1;

            ITEMS.add(new StartGamePacket.ItemEntry("geysermc:furnace_minecart", (short) furnaceMinecartId, true));
            ITEM_ENTRIES.put(javaFurnaceMinecartId, new ItemEntry("minecraft:furnace_minecart", "geysermc:furnace_minecart", javaFurnaceMinecartId,
                    furnaceMinecartId, 0, false));
            creativeItems.add(ItemData.fromNet(netId, furnaceMinecartId, (short) 0, 1, null));

            NbtMapBuilder builder = NbtMap.builder();
            builder.putString("name", "geysermc:furnace_minecart")
                    .putInt("id", furnaceMinecartId);

            NbtMapBuilder componentBuilder = NbtMap.builder();
            // Conveniently, as of 1.16.200, the furnace minecart has a texture AND translation string already.
            componentBuilder.putCompound("minecraft:icon", NbtMap.builder().putString("texture", "minecart_furnace").build());
            componentBuilder.putCompound("minecraft:display_name", NbtMap.builder().putString("value", "item.minecartFurnace.name").build());

            // Indicate that the arm animation should play on rails
            List<NbtMap> useOnTag = Collections.singletonList(NbtMap.builder().putString("tags", "q.any_tag('rail')").build());
            componentBuilder.putCompound("minecraft:entity_placer", NbtMap.builder()
                    .putList("dispense_on", NbtType.COMPOUND, useOnTag)
                    .putString("entity", "minecraft:minecart")
                    .putList("use_on", NbtType.COMPOUND, useOnTag)
            .build());

            NbtMapBuilder itemProperties = NbtMap.builder();
            // We always want to allow offhand usage when we can - matches Java Edition
            itemProperties.putBoolean("allow_off_hand", true);
            itemProperties.putBoolean("hand_equipped", false);
            itemProperties.putInt("max_stack_size", 1);
            itemProperties.putString("creative_group", "itemGroup.name.minecart");
            itemProperties.putInt("creative_category", 4); // 4 - "Items"

            componentBuilder.putCompound("item_properties", itemProperties.build());
            builder.putCompound("components", componentBuilder.build());
            FURNACE_MINECART_DATA = new ComponentItemData("geysermc:furnace_minecart", builder.build());
        } else {
            FURNACE_MINECART_DATA = null;
        }

        CREATIVE_ITEMS = creativeItems.toArray(new ItemData[0]);

        ITEM_NAMES = itemNames.toArray(new String[0]);
    }

    /**
     * Gets an {@link ItemEntry} from the given {@link ItemStack}.
     *
     * @param stack the item stack
     * @return an item entry from the given item stack
     */
    public static ItemEntry getItem(ItemStack stack) {
        return ITEM_ENTRIES.get(stack.getId());
    }

    /**
     * Gets an {@link ItemEntry} from the given {@link ItemData}.
     *
     * @param data the item data
     * @return an item entry from the given item data
     */
    public static ItemEntry getItem(ItemData data) {
        for (ItemEntry itemEntry : ITEM_ENTRIES.values()) {
            if (itemEntry.getBedrockId() == data.getId() && (itemEntry.getBedrockData() == data.getDamage() ||
                    // Make exceptions for potions and tipped arrows, whose damage values can vary
                    (itemEntry.getJavaIdentifier().endsWith("potion") || itemEntry.getJavaIdentifier().equals("minecraft:arrow")))) {
                if (!JAVA_ONLY_ITEMS.contains(itemEntry.getJavaIdentifier())) {
                    // From a Bedrock item data, we aren't getting one of these items
                    return itemEntry;
                }
            }
        }

        // This will hide the message when the player clicks with an empty hand
        if (data.getId() != 0 && data.getDamage() != 0) {
            GeyserConnector.getInstance().getLogger().debug("Missing mapping for bedrock item " + data.getId() + ":" + data.getDamage());
        }
        return ItemEntry.AIR;
    }

    /**
     * Gets an {@link ItemEntry} from the given Minecraft: Java Edition
     * block state identifier.
     *
     * @param javaIdentifier the block state identifier
     * @return an item entry from the given java edition identifier
     */
    public static ItemEntry getItemEntry(String javaIdentifier) {
        return JAVA_IDENTIFIER_MAP.computeIfAbsent(javaIdentifier, key -> {
            for (ItemEntry entry : ITEM_ENTRIES.values()) {
                if (entry.getJavaIdentifier().equals(key)) {
                    return entry;
                }
            }
            return null;
        });
    }

    /**
     * Gets a Bedrock {@link ItemData} from a {@link JsonNode}
     * @param itemNode the JSON node that contains ProxyPass-compatible Bedrock item data
     * @return
     */
    public static ItemData getBedrockItemFromJson(JsonNode itemNode) {
        int count = 1;
        short damage = 0;
        NbtMap tag = null;
        if (itemNode.has("damage")) {
            damage = itemNode.get("damage").numberValue().shortValue();
        }
        if (itemNode.has("count")) {
            count = itemNode.get("count").asInt();
        }
        if (itemNode.has("nbt_b64")) {
            byte[] bytes = Base64.getDecoder().decode(itemNode.get("nbt_b64").asText());
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            try {
                tag = (NbtMap) NbtUtils.createReaderLE(bais).readTag();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return ItemData.of(itemNode.get("id").asInt(), damage, count, tag);
    }
}
