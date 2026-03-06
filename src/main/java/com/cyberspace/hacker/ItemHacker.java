package com.cyberspace.hacker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ItemHacker {

    public static final Set<net.minecraft.world.item.Item> BANNED_ITEMS = ConcurrentHashMap.newKeySet();
    public static final Set<net.minecraft.world.item.Item> UNBANNED_ITEMS = ConcurrentHashMap.newKeySet();

    // =========================================
    // ЭПОХА И БАНК ДАННЫХ
    // =========================================
    public static int ITEM_EPOCH = 0;

    // Карточка памяти для предмета. Хранит точные координаты и происхождение.
    public record ItemRecord(ItemStack stack, String dimension, BlockPos fallbackPos, UUID entityId, int slot, String type) {}

    public static final Map<Item, List<ItemRecord>> SAVED_ITEMS = new ConcurrentHashMap<>();

    // =========================================
    // ХИРУРГИЧЕСКИЙ СТОЛ ПРЕДМЕТОВ
    // =========================================
    public static ItemStack processItemStack(ItemStack stack, boolean[] changedFlag) {
        if (stack.isEmpty()) return stack;

        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();

        int itemEpoch = tag.getInt("itemhacker_epoch").orElse(0);
        if (itemEpoch == ITEM_EPOCH) return stack;

        if (BANNED_ITEMS.contains(stack.getItem())) {
            // Для конвейерных "свежих" предметов мы не сохраняем позиции (предотвращаем дюпы)
            changedFlag[0] = true;
            return ItemStack.EMPTY;
        }

        tag.putInt("itemhacker_epoch", ITEM_EPOCH);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        changedFlag[0] = true;

        return stack;
    }

    // =========================================
    // ОБРАБОТЧИКИ ИЗ КОНВЕЙЕРА
    // =========================================
    public static void processPlayerInventory(ServerPlayer player) {
        boolean changed = false;
        boolean[] flag = new boolean[1];

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            flag[0] = false;
            ItemStack result = processItemStack(player.getInventory().getItem(i), flag);
            if (flag[0]) {
                player.getInventory().setItem(i, result);
                changed = true;
            }
        }
        if (changed) player.inventoryMenu.broadcastChanges();
    }

    public static void processBlockEntity(BlockEntity be) {
        if (be instanceof Container container) {
            boolean changed = false;
            boolean[] flag = new boolean[1];

            for (int i = 0; i < container.getContainerSize(); i++) {
                flag[0] = false;
                ItemStack result = processItemStack(container.getItem(i), flag);
                if (flag[0]) {
                    container.setItem(i, result);
                    changed = true;
                }
            }
            if (changed) be.setChanged();
        }
    }

    public static void processEntity(Entity e) {
        if (e instanceof ServerPlayer) return;
        boolean[] flag = new boolean[1];

        if (e instanceof ItemEntity item) {
            ItemStack result = processItemStack(item.getItem(), flag);
            if (flag[0]) {
                if (result.isEmpty()) item.discard();
                else item.setItem(result);
            }
        } else if (e instanceof ItemFrame frame) {
            ItemStack result = processItemStack(frame.getItem(), flag);
            if (flag[0]) frame.setItem(result);
        } else if (e instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                flag[0] = false;
                ItemStack result = processItemStack(living.getItemBySlot(slot), flag);
                if (flag[0]) living.setItemSlot(slot, result);
            }
        }
    }

    // =========================================
    // ФАЗА 1: РАЗВЕДКА И ИЗЪЯТИЕ (Без изменения статуса бана)
    // =========================================
    public static Set<Item> scanAndCollect(MinecraftServer server, Set<Item> targetItems) {
        Set<Item> foundAndDeletedItems = new HashSet<>();
        if (targetItems == null || targetItems.isEmpty()) return foundAndDeletedItems;

        // --- 1. Игроки ---
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean playerChanged = false;
            String dim = player.level().dimension().identifier().toString();

            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && targetItems.contains(stack.getItem())) {
                    Item currentItem = stack.getItem();
                    List<ItemRecord> savedRecords = SAVED_ITEMS.computeIfAbsent(currentItem, k -> new ArrayList<>());

                    try { savedRecords.add(new ItemRecord(stack.copy(), dim, player.blockPosition(), player.getUUID(), i, "PLAYER")); } catch (Exception ex) {}
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                    foundAndDeletedItems.add(currentItem);
                    playerChanged = true;
                }
            }
            if (playerChanged) player.inventoryMenu.broadcastChanges();
        }

        // --- 2. Мир и Сундуки ---
        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().identifier().toString();

            for (Entity e : level.getAllEntities()) {
                if (e instanceof ServerPlayer) continue;

                if (e instanceof ItemEntity item && !item.getItem().isEmpty() && targetItems.contains(item.getItem().getItem())) {
                    Item currentItem = item.getItem().getItem();
                    List<ItemRecord> savedRecords = SAVED_ITEMS.computeIfAbsent(currentItem, k -> new ArrayList<>());
                    try { savedRecords.add(new ItemRecord(item.getItem().copy(), dim, item.blockPosition(), null, -1, "DROP")); } catch (Exception ex) {}
                    item.discard();
                    foundAndDeletedItems.add(currentItem);

                } else if (e instanceof ItemFrame frame && !frame.getItem().isEmpty() && targetItems.contains(frame.getItem().getItem())) {
                    Item currentItem = frame.getItem().getItem();
                    List<ItemRecord> savedRecords = SAVED_ITEMS.computeIfAbsent(currentItem, k -> new ArrayList<>());
                    try { savedRecords.add(new ItemRecord(frame.getItem().copy(), dim, frame.blockPosition(), frame.getUUID(), -1, "ENTITY")); } catch (Exception ex) {}
                    frame.setItem(ItemStack.EMPTY);
                    foundAndDeletedItems.add(currentItem);

                } else if (e instanceof LivingEntity living) {
                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        ItemStack stack = living.getItemBySlot(slot);
                        if (!stack.isEmpty() && targetItems.contains(stack.getItem())) {
                            Item currentItem = stack.getItem();
                            List<ItemRecord> savedRecords = SAVED_ITEMS.computeIfAbsent(currentItem, k -> new ArrayList<>());
                            try { savedRecords.add(new ItemRecord(stack.copy(), dim, living.blockPosition(), living.getUUID(), slot.ordinal(), "LIVING")); } catch (Exception ex) {}
                            living.setItemSlot(slot, ItemStack.EMPTY);
                            foundAndDeletedItems.add(currentItem);
                        }
                    }
                }
            }

            // Сканируем сундуки рядом с игроками
            int viewDist = server.getPlayerList().getViewDistance();
            Set<Long> scannedChunks = new HashSet<>();

            for (ServerPlayer player : level.players()) {
                ChunkPos pPos = player.chunkPosition();
                for (int x = -viewDist; x <= viewDist; x++) {
                    for (int z = -viewDist; z <= viewDist; z++) {
                        long chunkPosLong = ChunkPos.asLong(pPos.x + x, pPos.z + z);
                        if (scannedChunks.add(chunkPosLong)) {
                            LevelChunk chunk = level.getChunkSource().getChunkNow(pPos.x + x, pPos.z + z);
                            if (chunk != null) {
                                for (BlockEntity be : chunk.getBlockEntities().values()) {
                                    if (be instanceof Container container) {
                                        for (int i = 0; i < container.getContainerSize(); i++) {
                                            ItemStack stack = container.getItem(i);
                                            if (!stack.isEmpty() && targetItems.contains(stack.getItem())) {
                                                Item currentItem = stack.getItem();
                                                List<ItemRecord> savedRecords = SAVED_ITEMS.computeIfAbsent(currentItem, k -> new ArrayList<>());
                                                try { savedRecords.add(new ItemRecord(stack.copy(), dim, be.getBlockPos(), null, i, "CONTAINER")); } catch (Exception ex) {}
                                                container.setItem(i, ItemStack.EMPTY);
                                                be.setChanged();
                                                foundAndDeletedItems.add(currentItem);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return foundAndDeletedItems;
    }

    // =========================================
    // ФАЗА 2: ИСПОЛНЕНИЕ ПРИГОВОРА
    // =========================================
    public static void applyState(MinecraftServer server, Set<Item> targetItems, boolean shouldBan, boolean allowFallback) {
        if (targetItems == null || targetItems.isEmpty()) return;

        for (Item targetItem : targetItems) {
            List<ItemRecord> savedRecords = SAVED_ITEMS.computeIfAbsent(targetItem, k -> new ArrayList<>());

            if (shouldBan) {
                // БАНИМ (Предметы уже были физически изъяты в scanAndCollect, просто фиксируем статус)
                UNBANNED_ITEMS.remove(targetItem);
                BANNED_ITEMS.add(targetItem);
                System.out.println("[ItemHacker] ПРЕДМЕТ " + targetItem.toString() + " ЗАБАНЕН! Сохранено записей: " + savedRecords.size());
            } else {
                // РАЗБАНИВАЕМ И ВОЗВРАЩАЕМ
                BANNED_ITEMS.remove(targetItem);
                UNBANNED_ITEMS.add(targetItem);
                System.out.println("[ItemHacker] ПРЕДМЕТ " + targetItem.toString() + " РАЗБАНЕН!");

                if (!savedRecords.isEmpty()) {
                    for (ItemRecord record : savedRecords) {
                        try {
                            ResourceKey<net.minecraft.world.level.Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(record.dimension));
                            ServerLevel targetLevel = server.getLevel(dimKey);
                            if (targetLevel == null) continue;

                            boolean restored = false;
                            switch (record.type) {
                                case "CONTAINER":
                                    BlockEntity be = targetLevel.getBlockEntity(record.fallbackPos);
                                    if (be instanceof Container container && container.getItem(record.slot).isEmpty()) {
                                        container.setItem(record.slot, record.stack);
                                        be.setChanged();
                                        restored = true;
                                    }
                                    break;
                                case "PLAYER":
                                    ServerPlayer p = server.getPlayerList().getPlayer(record.entityId);
                                    if (p != null) {
                                        if (p.getInventory().getItem(record.slot).isEmpty()) p.getInventory().setItem(record.slot, record.stack);
                                        else p.getInventory().placeItemBackInInventory(record.stack);
                                        restored = true;
                                    }
                                    break;
                                case "ENTITY":
                                    Entity e = targetLevel.getEntity(record.entityId);
                                    if (e instanceof ItemFrame frame && frame.getItem().isEmpty()) { frame.setItem(record.stack); restored = true; }
                                    break;
                                case "LIVING":
                                    Entity living = targetLevel.getEntity(record.entityId);
                                    if (living instanceof LivingEntity le) {
                                        EquipmentSlot eqSlot = EquipmentSlot.values()[record.slot];
                                        if (le.getItemBySlot(eqSlot).isEmpty()) { le.setItemSlot(eqSlot, record.stack); restored = true; }
                                    }
                                    break;
                            }
                            if (!restored) {
                                ItemEntity drop = new ItemEntity(targetLevel, record.fallbackPos.getX() + 0.5, record.fallbackPos.getY() + 0.5, record.fallbackPos.getZ() + 0.5, record.stack);
                                targetLevel.addFreshEntity(drop);
                            }
                        } catch (Exception e) {
                            System.err.println("[ItemHacker] Ошибка при возврате предмета.");
                        }
                    }
                    savedRecords.clear();
                } else if (allowFallback) { // <--- ВОТ ТУТ
                    // Запасной план: выдаем стак
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        player.getInventory().placeItemBackInInventory(new ItemStack(targetItem, targetItem.getDefaultMaxStackSize()));
                    }
                }
            }
        }
        ITEM_EPOCH++;
    }
}