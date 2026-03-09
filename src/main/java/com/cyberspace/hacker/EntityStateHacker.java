package com.cyberspace.hacker;

import com.cyberspace.utils.CyberSpaceParser.AttributeContext;
import com.cyberspace.utils.CyberSpaceParser.EntityFlagContext;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Consumer;

import static com.cyberspace.CyberSpaceMod.SERVER;

public class EntityStateHacker {

    // ================================================
    // СОСТОЯНИЕ
    // ================================================
    public static int GLOBAL_EPOCH = 1;
    private static final WeakHashMap<Entity, Integer> ENTITY_EPOCHS = new WeakHashMap<>();

    public static final Map<String, Double> ACTIVE_FLAGS = new HashMap<>();
    public static final Map<AttributeContext, AttributeModifier> ACTIVE_MODIFIERS = new HashMap<>();

    // Кэши объектов для динамики
    private static final Vec3 WEB_SPEED = new Vec3(0.25, 0.05, 0.25);
    private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();

    // ID модификаторов скорости (используем модификаторы вместо setBaseValue,
    // чтобы не трогать реальный базовый показатель сущности)
    private static final Identifier SPEED_LIMIT_ID   = Identifier.fromNamespaceAndPath("cyberspace", "speed_ban_speed_limit");
    private static final Identifier SPEED_MUSCLES_ID  = Identifier.fromNamespaceAndPath("cyberspace", "speed_ban_muscles");
    private static final Identifier FREEZE_KB_ID      = Identifier.fromNamespaceAndPath("cyberspace", "freeze_kb_resist");
    private static final Identifier FREEZE_FLYING_ID  = Identifier.fromNamespaceAndPath("cyberspace", "freeze_flying_speed");

    // Per-entity состояние для ban_speed_limit
    private static final WeakHashMap<LivingEntity, Double> SPEED_SCORES   = new WeakHashMap<>();
    private static final WeakHashMap<LivingEntity, Vec3>   PREV_POSITIONS = new WeakHashMap<>();

    // Сохранённые базовые значения movement_speed (для корректного восстановления)
    private static final WeakHashMap<LivingEntity, Double> SAVED_SPEEDS   = new WeakHashMap<>();


    // ================================================
    // СКОМПИЛИРОВАННЫЕ ПЛАНЫ ТИКА
    // Пересобираются только при смене эпохи (т.е. при вводе команды).
    // В остальное время — просто итерация по списку без единого Map lookup.
    // ================================================
    private static int COMPILED_EPOCH = 0;

    private static List<Consumer<Entity>>       entityDynamic  = List.of();
    private static List<Consumer<LivingEntity>> livingDynamic  = List.of();
    private static List<Consumer<Entity>>       entityStatic   = List.of();
    private static List<Consumer<LivingEntity>> livingStatic   = List.of();
    private static List<Consumer<ServerPlayer>> playerDynamic  = List.of();
    private static List<Consumer<ServerPlayer>> playerStatic   = List.of();
    private static List<Consumer<ServerPlayer>> networkDynamic = List.of();

    // ================================================
    // ПЕРЕКЛЮЧЕНИЕ ФЛАГОВ
    // ================================================
    public static void toggleFlag(EntityFlagContext ctx) {
        if (ctx.isComposite()) {
            // Флаговые атомы: каждый переключается независимо
            if (ctx.atoms() != null) {
                for (String atom : ctx.atoms()) {
                    if (ACTIVE_FLAGS.containsKey(atom)) {
                        ACTIVE_FLAGS.remove(atom);
                        System.out.println("[EntityStateHacker] Атом снят: " + atom);
                    } else {
                        ACTIVE_FLAGS.put(atom, 1.0);
                        System.out.println("[EntityStateHacker] Атом наложен: " + atom);
                    }
                }
            }
            // Атрибутные атомы: ищем по ruleName и переключаем
            if (ctx.attrAtoms() != null) {
                for (String attrName : ctx.attrAtoms()) {
                    com.cyberspace.utils.CyberSpaceParser.ATTRIBUTE_RULES.stream()
                            .filter(a -> a.ruleName().equals(attrName))
                            .findFirst()
                            .ifPresent(EntityStateHacker::toggleAttributeInternal);
                }
            }
        } else {
            String name = ctx.flag();
            if (ACTIVE_FLAGS.containsKey(name)) {
                ACTIVE_FLAGS.remove(name);
                System.out.println("[EntityStateHacker] Снято: " + name);
            } else {
                ACTIVE_FLAGS.put(name, ctx.absurdVal());
                System.out.println("[EntityStateHacker] Наложено: " + name);
            }
        }
        GLOBAL_EPOCH++;
    }

    private static void toggleAttributeInternal(AttributeContext ctx) {
        Identifier id = Identifier.fromNamespaceAndPath("cyberspace", "attr_" + ctx.ruleName());
        if (ACTIVE_MODIFIERS.containsKey(ctx)) {
            ACTIVE_MODIFIERS.remove(ctx);
            System.out.println("[EntityStateHacker] Атрибут снят: " + ctx.ruleName());
        } else {
            ACTIVE_MODIFIERS.put(ctx, new AttributeModifier(id, ctx.absurdVal(), AttributeModifier.Operation.ADD_VALUE));
            System.out.println("[EntityStateHacker] Атрибут наложен: " + ctx.ruleName());
        }
    }

    public static void toggleAttribute(MinecraftServer server, AttributeContext ctx) {
        toggleAttributeInternal(ctx);
        GLOBAL_EPOCH++;
    }

    // ================================================
    // КОМПИЛЯЦИЯ ПЛАНОВ ТИКА
    // ================================================
    private static boolean flag(String name) {
        return ACTIVE_FLAGS.getOrDefault(name, 0.0) > 0.0;
    }

    private static void recompile() {
        if (COMPILED_EPOCH == GLOBAL_EPOCH) return;

        if (!flag("ban_speed_limit")) {
            SPEED_SCORES.clear();
            PREV_POSITIONS.clear();
        }

        List<Consumer<Entity>>       ed = new ArrayList<>();
        List<Consumer<LivingEntity>> ld = new ArrayList<>();
        List<Consumer<Entity>>       es = new ArrayList<>();
        List<Consumer<LivingEntity>> ls = new ArrayList<>();
        List<Consumer<ServerPlayer>> pd = new ArrayList<>();
        List<Consumer<ServerPlayer>> ps = new ArrayList<>();
        List<Consumer<ServerPlayer>> nd = new ArrayList<>();

        // ============================================
        // ENTITY DYNAMIC
        // Всё что ванила сбрасывает каждый тик — переопределяем после неё.
        // ============================================

        // Атомы движения — каждый работает независимо.
        if (flag("no_gravity"))
            ed.add(e -> e.setNoGravity(true)); // dynamic для первого тика (static сделает персистентно)

        if (flag("zero_momentum")) {
            ed.add(e -> {
                e.setDeltaMovement(Vec3.ZERO);
                if (e instanceof ServerPlayer p)
                    p.connection.send(new ClientboundSetEntityMotionPacket(p.getId(), Vec3.ZERO));
            });
        } else if (flag("ban_speed_limit")) {
            // Ускорение даёт атрибут (см. livingStatic): ванила сама считает инпут по взгляду.
            // Здесь только страховочный cap — чтобы физика не улетела в NaN.
            ed.add(e -> {
                Vec3 move = e.getDeltaMovement();
                final double MAX_SPEED = 50.0;
                if (move.lengthSqr() > MAX_SPEED * MAX_SPEED) {
                    Vec3 capped = move.normalize().scale(MAX_SPEED);
                    e.setDeltaMovement(capped);
                    if (e instanceof ServerPlayer p)
                        p.connection.send(new ClientboundSetEntityMotionPacket(p.getId(), capped));
                }
            });
        }

        if (flag("stuck_in_web"))
            ed.add(e -> e.makeStuckInBlock(AIR_STATE, WEB_SPEED));
        if (flag("force_not_on_ground"))
            ed.add(e -> e.setOnGround(false));
        if (flag("force_iframes"))
            ed.add(e -> e.invulnerableTime = 20);
        if (flag("force_dismount"))
            ed.add(e -> { if (e.isVehicle()) e.ejectPassengers(); if (e.isPassenger()) e.stopRiding(); });
        if (flag("powder_snow"))
            ed.add(e -> e.setIsInPowderSnow(true));
        if (flag("force_elytra_pose"))
            ed.add(e -> e.setPose(Pose.FALL_FLYING));
        if (flag("sneaking"))
            ed.add(e -> e.setShiftKeyDown(true));
        if (flag("swimming"))
            ed.add(e -> e.setSwimming(true));
        if (flag("sprinting"))
            ed.add(e -> e.setSprinting(true));

        // Таймеры: захватываем значение сейчас, чтобы не лезть в Map каждый тик
        if (flag("fire_ticks")) {
            int val = ACTIVE_FLAGS.get("fire_ticks").intValue();
            ed.add(e -> e.setRemainingFireTicks(val));
        }
        if (flag("freeze_ticks")) {
            int val = ACTIVE_FLAGS.get("freeze_ticks").intValue();
            ed.add(e -> e.setTicksFrozen(val));
        }
        if (flag("air_supply")) {
            int val = ACTIVE_FLAGS.get("air_supply").intValue();
            ed.add(e -> e.setAirSupply(val));
        }
        if (flag("fall_distance")) {
            double val = ACTIVE_FLAGS.get("fall_distance");
            ed.add(e -> e.fallDistance = val);
        }
        if (flag("portal_cooldown")) {
            int val = ACTIVE_FLAGS.get("portal_cooldown").intValue();
            ed.add(e -> e.setPortalCooldown(val));
        }

        // ============================================
        // ENTITY STATIC
        // SynchedEntityData и plain-поля — ставятся один раз при смене эпохи.
        // ============================================
        {
            // Захватываем целевое состояние каждого поля в момент компиляции
            boolean noPhysics = flag("no_physics");
            boolean noGravity = flag("no_gravity");
            boolean glowing       = flag("glowing");
            boolean invisible     = flag("invisible");
            boolean invulnerable  = flag("invulnerable");
            boolean silent        = flag("silent");
            boolean blocksBuild   = flag("blocks_building");
            boolean eraseNames    = flag("erase_names");

            // Cleanup для динамических флагов: запускается один раз при их снятии
            boolean clearFire     = !flag("fire_ticks");
            boolean clearFreeze   = !flag("freeze_ticks");
            boolean clearSneak    = !flag("sneaking");
            boolean clearSwim     = !flag("swimming");
            boolean clearSprint   = !flag("sprinting");
            boolean clearElytra   = !flag("force_elytra_pose");

            es.add(e -> {
                e.noPhysics = noPhysics;
                e.setNoGravity(noGravity);
                e.setGlowingTag(glowing);
                e.setInvisible(invisible);
                e.setInvulnerable(invulnerable);
                e.setSilent(silent);
                e.blocksBuilding = blocksBuild;

                if (clearFire)   e.clearFire();
                if (clearFreeze) e.clearFreeze();
                if (clearSneak)  e.setShiftKeyDown(false);
                if (clearSwim)   e.setSwimming(false);
                if (clearSprint) e.setSprinting(false);
                if (clearElytra && e.getPose() == Pose.FALL_FLYING) e.setPose(Pose.STANDING);
                if (eraseNames)  { e.setCustomName(null); e.setCustomNameVisible(false); }
            });
        }

        // ============================================
        // LIVING DYNAMIC
        // AI и механика LivingEntity сбрасывают эти значения каждый тик.
        // ============================================
        if (flag("ban_mind"))
            ld.add(e -> e.setNoActionTime(100));
        if (flag("ban_usage"))
            ld.add(e -> { if (e.isUsingItem()) e.stopUsingItem(); });
        if (flag("ban_health"))
            ld.add(e -> e.setHealth(0.0f));
        if (flag("ban_absorption"))
            ld.add(e -> e.setAbsorptionAmount(0.0f));
        if (flag("no_jump"))
            ld.add(e -> e.setJumping(false));
        if (flag("ban_swing"))
            ld.add(e -> { e.swinging = false; e.swingTime = 0; e.attackAnim = 0.0f; });
        if (flag("ban_memory"))
            ld.add(e -> { e.setLastHurtByMob(null); e.setLastHurtMob(null); e.setLastHurtByPlayer((Player) null, 0); });
        if (flag("buff_pain"))
            ld.add(e -> { e.hurtTime = 10; e.hurtDuration = 10; });
        if (flag("buff_death"))
            ld.add(e -> e.deathTime = 19);
        if (flag("ban_speed_limit")) {
            // Детектируем движение по позиции — единственный надёжный серверный источник.
            // getDeltaMovement() для ServerPlayer почти всегда 0 (сервер не симулирует его физику),
            // поэтому сравниваем реальную позицию с предыдущим тиком.
            final double RAMP_UP   = 1.0;
            final double MAX_MOD   = 249000.0;
            ld.add(e -> {
                Vec3 pos  = e.position();
                Vec3 prev = PREV_POSITIONS.getOrDefault(e, pos);
                PREV_POSITIONS.put(e, pos);
                double mod = SPEED_SCORES.getOrDefault(e, 0.0);
                boolean moving = e instanceof ServerPlayer
                        ? pos.distanceToSqr(prev) > 0.001
                        : e.getDeltaMovement().lengthSqr() > 0.0001;
                mod = moving ? Math.min(mod + RAMP_UP, MAX_MOD) : 0.0;
                SPEED_SCORES.put(e, mod);
                AttributeInstance spd = e.getAttribute(Attributes.MOVEMENT_SPEED);
                if (spd != null) {
                    spd.removeModifier(SPEED_LIMIT_ID);
                    if (mod > 0.0)
                        spd.addTransientModifier(new AttributeModifier(SPEED_LIMIT_ID, mod, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                }
            });
        }

        // ============================================
        // LIVING STATIC
        // Атрибуты и счётчики — достаточно обновить один раз при смене эпохи.
        // ============================================
        {
            boolean hasBanSpeedLimit = flag("ban_speed_limit");
            boolean atomMuscles      = flag("no_muscles");
            boolean atomKnockback    = flag("no_knockback");
            boolean hasArrows        = flag("buff_arrows");
            boolean hasStingers      = flag("buff_stingers");
            int arrowCount   = hasArrows   ? ACTIVE_FLAGS.get("buff_arrows").intValue()   : 0;
            int stingerCount = hasStingers ? ACTIVE_FLAGS.get("buff_stingers").intValue() : 0;

            // Модификаторы скорости: пересобираем при каждой смене эпохи
            AttributeModifier speedLimitMod = null; // рамп обрабатывается в livingDynamic
            boolean hasZeroSpeed = atomMuscles;
            boolean atomFlying = flag("no_flying");
            AttributeModifier flyingSpeedMod = atomFlying
                    ? new AttributeModifier(FREEZE_FLYING_ID, -1000000.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) : null;

            ls.add(e -> {
                AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.removeModifier(SPEED_LIMIT_ID);
                    speed.removeModifier(SPEED_MUSCLES_ID);
                    if (hasZeroSpeed) {
                        // Сохраняем оригинальный baseValue только один раз
                        SAVED_SPEEDS.computeIfAbsent(e, k -> speed.getBaseValue());
                        speed.setBaseValue(0.0);
                    } else {
                        // Восстанавливаем сохранённое значение если флаг снят
                        Double saved = SAVED_SPEEDS.remove(e);
                        if (saved != null) speed.setBaseValue(saved);
                        if (speedLimitMod != null) speed.addPermanentModifier(speedLimitMod);
                    }
                }
                AttributeInstance flying = e.getAttribute(Attributes.FLYING_SPEED);
                if (flying != null) {
                    flying.removeModifier(FREEZE_FLYING_ID);
                    if (flyingSpeedMod != null) flying.addPermanentModifier(flyingSpeedMod);
                }

                // Нокбэк: полное сопротивление, чтобы удар не двигал сущность.
                AttributeInstance kb = e.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
                if (kb != null) {
                    kb.removeModifier(FREEZE_KB_ID);
                    if (atomKnockback) kb.addPermanentModifier(new AttributeModifier(FREEZE_KB_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
                }

                e.setArrowCount(arrowCount);
                e.setStingerCount(stingerCount);

                // Атрибуты: счищаем все наши модификаторы, накатываем только активные
                for (AttributeContext ctx : com.cyberspace.utils.CyberSpaceParser.ATTRIBUTE_RULES) {
                    AttributeInstance inst = e.getAttribute(ctx.attribute());
                    if (inst != null)
                        inst.removeModifier(Identifier.fromNamespaceAndPath("cyberspace", "attr_" + ctx.ruleName()));
                }
                for (var entry : ACTIVE_MODIFIERS.entrySet()) {
                    AttributeInstance inst = e.getAttribute(entry.getKey().attribute());
                    if (inst != null)
                        inst.addPermanentModifier(entry.getValue());
                }
            });
        }

        // ============================================
        // PLAYER DYNAMIC
        // Голод, опыт, инвентарь — меняются игровой механикой каждый тик.
        // ============================================
        if (flag("ban_xp_pickup"))
            pd.add(p -> p.takeXpDelay = 32000);
        if (flag("ban_sleep"))
            pd.add(p -> { if (p.isSleeping()) p.stopSleeping(); });
        if (flag("ban_experience"))
            pd.add(p -> { if (p.experienceLevel > 0 || p.experienceProgress > 0 || p.totalExperience > 0) { p.setExperienceLevels(0); p.experienceProgress = 0; p.totalExperience = 0; } });
        if (flag("ban_satiety"))
            pd.add(p -> { p.getFoodData().setFoodLevel(6); p.getFoodData().setSaturation(0); });
        if (flag("ban_exhaustion"))
            pd.add(p -> { p.getFoodData().setFoodLevel(20); p.getFoodData().setSaturation(20); });
        if (flag("ban_possession"))
            pd.add(p -> p.getInventory().clearContent());
        if (flag("ban_vanity"))
            pd.add(p -> p.setScore(0));
        if (flag("ban_companions"))
            pd.add(p -> { p.setShoulderParrotLeft(Optional.empty()); p.setShoulderParrotRight(Optional.empty()); });
        if (flag("ban_ender_chest"))
            pd.add(p -> { if (!p.getEnderChestInventory().isEmpty()) p.getEnderChestInventory().clearContent(); });
        if (flag("ban_containers"))
            pd.add(p -> { if (p.hasContainerOpen()) p.closeContainer(); });
        if (flag("ban_grip"))
            pd.add(p -> { ItemStack main = p.getMainHandItem(); if (!main.isEmpty()) { p.drop(main.copy(), true, false); p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); } });
        if (flag("ban_grave"))
            pd.add(p -> { if (p.getLastDeathLocation().isPresent()) p.setLastDeathLocation(Optional.empty()); });
        if (flag("ban_patience"))
            pd.add(p -> { ItemStack main = p.getMainHandItem(); ItemStack off = p.getOffhandItem(); if (!main.isEmpty()) p.getCooldowns().addCooldown(main, 100); if (!off.isEmpty()) p.getCooldowns().addCooldown(off, 100); });

        // ============================================
        // PLAYER STATIC
        // Abilities и флаги отладки — персистентны, достаточно одного раза.
        // ============================================
        {
            boolean banCoords       = flag("ban_coords");
            boolean forceFlight     = flag("force_flight");
            boolean banBuild        = flag("ban_build_ability");
            boolean banVulnerability = flag("ban_vulnerability");
            boolean banLimits       = flag("ban_limits");

            ps.add(p -> {
                if (p.isReducedDebugInfo() != banCoords) {
                    p.setReducedDebugInfo(banCoords);
                    p.connection.send(new ClientboundEntityEventPacket(p, (byte)(banCoords ? 22 : 23)));
                }

                boolean changed = false;
                GameType mode = p.gameMode.getGameModeForPlayer();

                if (mode != GameType.CREATIVE && mode != GameType.SPECTATOR && p.getAbilities().mayfly != forceFlight) {
                    p.getAbilities().mayfly = forceFlight;
                    if (!forceFlight) p.getAbilities().flying = false;
                    changed = true;
                }
                if (mode != GameType.ADVENTURE && p.getAbilities().mayBuild == banBuild) {
                    p.getAbilities().mayBuild = !banBuild;
                    changed = true;
                }
                if (mode != GameType.CREATIVE && p.getAbilities().invulnerable != banVulnerability) {
                    p.getAbilities().invulnerable = banVulnerability;
                    changed = true;
                }
                if (mode != GameType.CREATIVE && p.getAbilities().instabuild != banLimits) {
                    p.getAbilities().instabuild = banLimits;
                    changed = true;
                }
                if (changed) p.onUpdateAbilities();
            });
        }

        // ============================================
        // NETWORK DYNAMIC
        // Пакеты не персистируют на клиенте — всё только в динамике.
        // ============================================
        if (flag("no_muscles"))
            nd.add(p -> { AttributeInstance spd = p.getAttribute(Attributes.MOVEMENT_SPEED); if (spd != null) p.connection.send(new ClientboundUpdateAttributesPacket(p.getId(), List.of(spd))); });
        if (flag("ban_sound"))
            nd.add(p -> p.connection.send(new ClientboundStopSoundPacket(null, null)));
        if (flag("ban_horizon"))
            nd.add(p -> p.connection.send(new ClientboundSetChunkCacheRadiusPacket(2)));
        if (flag("ban_windows"))
            nd.add(p -> { if (p.containerMenu != p.inventoryMenu) { p.connection.send(new ClientboundContainerClosePacket(p.containerMenu.containerId)); p.closeContainer(); } });
        if (flag("ban_xp_ui"))
            nd.add(p -> p.connection.send(new ClientboundSetExperiencePacket(0.0f, 0, 0)));
        if (flag("ban_vitality_ui"))
            nd.add(p -> p.connection.send(new ClientboundSetHealthPacket(1.0f, 0, 0.0f)));
        if (flag("ban_choice"))
            nd.add(p -> p.connection.send(new ClientboundSetHeldSlotPacket(0)));
        if (flag("ban_society"))
            nd.add(p -> { List<UUID> uuids = SERVER.getPlayerList().getPlayers().stream().map(Entity::getUUID).toList(); p.connection.send(new ClientboundPlayerInfoRemovePacket(uuids)); });
        if (flag("illusion_shake"))
            nd.add(p -> p.connection.send(new ClientboundHurtAnimationPacket(p)));
        if (flag("illusion_rain"))
            nd.add(p -> { p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0f)); p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 1.0f)); });
        if (flag("illusion_night"))
            nd.add(p -> p.connection.send(new ClientboundSetTimePacket(p.level().getGameTime(), -18000L, false)));
        if (flag("red_screen"))
            nd.add(p -> { WorldBorder fb = new WorldBorder(); fb.setCenter(p.getX(), p.getZ()); fb.setSize(1000000.0); p.connection.send(new ClientboundSetBorderWarningDistancePacket(fb)); });
        if (flag("ban_text_ui"))
            nd.add(p -> p.connection.send(new ClientboundClearTitlesPacket(true)));
        if (flag("ban_momentum"))
            nd.add(p -> p.connection.send(new ClientboundSetEntityMotionPacket(p.getId(), Vec3.ZERO)));
        if (flag("no_client_speed"))
            nd.add(p -> { Abilities a = p.getAbilities(); float saved = a.getWalkingSpeed(); a.setWalkingSpeed(0.0f); p.connection.send(new ClientboundPlayerAbilitiesPacket(a)); a.setWalkingSpeed(saved); });
        if (flag("illusion_void"))
            nd.add(p -> p.connection.send(new ClientboundForgetLevelChunkPacket(p.chunkPosition())));
        if (flag("ban_weapon_ui"))
            nd.add(p -> p.connection.send(new ClientboundSetEquipmentPacket(p.getId(), List.of(Pair.of(EquipmentSlot.MAINHAND, ItemStack.EMPTY)))));
        if (flag("ban_freedom"))
            nd.add(p -> { WorldBorder prison = new WorldBorder(); prison.setCenter(p.getX(), p.getZ()); prison.setSize(1.0); p.connection.send(new ClientboundSetBorderSizePacket(prison)); });
        if (flag("ban_game"))
            nd.add(p -> p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.WIN_GAME, 0.0f)));
        if (flag("ban_history"))
            nd.add(p -> p.connection.send(new ClientboundSystemChatPacket(Component.literal(" "), false)));
        if (flag("ban_neck"))
            nd.add(p -> p.connection.send(new ClientboundRotateHeadPacket(p, (byte) 0)));
        if (flag("ban_vision"))
            nd.add(p -> p.connection.send(new ClientboundUpdateMobEffectPacket(p.getId(), new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false, false), false)));
        if (flag("ban_sky"))
            nd.add(p -> p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 1.0f)));
        if (flag("illusion_combat"))
            nd.add(p -> p.connection.send(ClientboundPlayerCombatEnterPacket.INSTANCE));
        if (flag("ban_demo"))
            nd.add(p -> p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.DEMO_EVENT, 0.0f)));
        if (flag("ban_f5"))
            nd.add(p -> p.connection.send(new ClientboundSetCameraPacket(p)));
        if (flag("ban_sobriety"))
            nd.add(p -> p.connection.send(new ClientboundUpdateMobEffectPacket(p.getId(), new MobEffectInstance(MobEffects.NAUSEA, 100, 0, false, false, false), false)));
        if (flag("ban_self_render"))
            nd.add(p -> p.connection.send(new ClientboundRemoveEntitiesPacket(p.getId())));
        if (flag("phantom_guardian"))
            nd.add(p -> p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT, 1.0f)));
        if (flag("phantom_arrows"))
            nd.add(p -> p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.PUFFER_FISH_STING, 0.0f)));
        if (flag("phantom_tool_break"))
            nd.add(p -> p.connection.send(new ClientboundEntityEventPacket(p, (byte) 47)));
        if (flag("phantom_end"))
            nd.add(p -> p.connection.send(new ClientboundLevelEventPacket(1038, p.blockPosition(), 0, false)));
        if (flag("phantom_smoke"))
            nd.add(p -> p.connection.send(new ClientboundEntityEventPacket(p, (byte) 60)));
        if (flag("illusion_level"))
            nd.add(p -> p.connection.send(new ClientboundSetExperiencePacket(1.0f, 1000, 1000)));

        // Фиксируем скомпилированные планы
        entityDynamic  = List.copyOf(ed);
        livingDynamic  = List.copyOf(ld);
        entityStatic   = List.copyOf(es);
        livingStatic   = List.copyOf(ls);
        playerDynamic  = List.copyOf(pd);
        playerStatic   = List.copyOf(ps);
        networkDynamic = List.copyOf(nd);

        COMPILED_EPOCH = GLOBAL_EPOCH;
    }

    // ================================================
    // ТОЧКИ ВХОДА
    // ================================================

    // Для всех сущностей кроме игроков (мобы, дроп, стрелы, вагонетки...)
    public static void processEntity(Entity entity) {
        recompile();

        for (var a : entityDynamic) a.accept(entity);
        if (entity instanceof LivingEntity living)
            for (var a : livingDynamic) a.accept(living);

        if (ENTITY_EPOCHS.getOrDefault(entity, 0) == GLOBAL_EPOCH) return;

        for (var a : entityStatic) a.accept(entity);
        if (entity instanceof LivingEntity living)
            for (var a : livingStatic) a.accept(living);

        ENTITY_EPOCHS.put(entity, GLOBAL_EPOCH);
    }

    // Для игроков — включает весь стек: entity + living + player + network
    public static void processPlayer(ServerPlayer player) {
        recompile();

        for (var a : entityDynamic)  a.accept(player);
        for (var a : livingDynamic)  a.accept(player);
        for (var a : playerDynamic)  a.accept(player);
        for (var a : networkDynamic) a.accept(player);

        if (ENTITY_EPOCHS.getOrDefault(player, 0) == GLOBAL_EPOCH) return;

        for (var a : entityStatic)  a.accept(player);
        for (var a : livingStatic)  a.accept(player);
        for (var a : playerStatic)  a.accept(player);

        ENTITY_EPOCHS.put(player, GLOBAL_EPOCH);
    }
}
