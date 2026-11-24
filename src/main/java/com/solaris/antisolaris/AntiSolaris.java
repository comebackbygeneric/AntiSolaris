package com.solaris.antisolaris;

import io.papermc.paper.ban.BanListType;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AntiSolaris extends JavaPlugin implements Listener {

    // Koruma Esikleri
    private static final double MAX_REACH = 4.25;
    private static final int MAX_AIR_TICKS = 35;
    private static final int BAN_THRESHOLD_VL = 8;

    // Oyuncu takip verileri
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Long> lastAlertTime = new HashMap<>();

    // TriggerBot & AimAssist verileri
    private final Map<UUID, Long> targetAcquiredTime = new HashMap<>();
    private final Map<UUID, UUID> lastLookedTarget = new HashMap<>();
    private final Map<UUID, Integer> fastTriggerStreak = new HashMap<>();
    private final Map<UUID, Float> lastYawDelta = new HashMap<>();
    private final Map<UUID, Integer> aimAssistStreak = new HashMap<>();

    // Xray verileri
    private final Map<UUID, Integer> stoneMined = new HashMap<>();
    private final Map<UUID, Integer> valuableOresMined = new HashMap<>();
    private final Map<UUID, Long> xrayWindowStart = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AntiSolaris 1.21.11 aktif edildi.");
    }

    @Override
    public void onDisable() {
        violations.clear();
        airTicks.clear();
        lastAlertTime.clear();
        targetAcquiredTime.clear();
        lastLookedTarget.clear();
        fastTriggerStreak.clear();
        lastYawDelta.clear();
        aimAssistStreak.clear();
        stoneMined.clear();
        valuableOresMined.clear();
        xrayWindowStart.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        violations.remove(uuid);
        airTicks.remove(uuid);
        lastAlertTime.remove(uuid);
        targetAcquiredTime.remove(uuid);
        lastLookedTarget.remove(uuid);
        fastTriggerStreak.remove(uuid);
        lastYawDelta.remove(uuid);
        aimAssistStreak.remove(uuid);
        stoneMined.remove(uuid);
        valuableOresMined.remove(uuid);
        xrayWindowStart.remove(uuid);
    }

    // 1. FLY + AIM ASSIST + TRIGGERBOT HEDEF TAKIBI
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMoveAndAim(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("antisolaris.bypass")) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();

        // --- AIM ASSIST & TRIGGERBOT TARAMASI ---
        float yawDiff = Math.abs(wrapAngle(to.getYaw() - from.getYaw()));
        float pitchDiff = Math.abs(to.getPitch() - from.getPitch());

        if (yawDiff > 0.5f && pitchDiff > 0.05f) {
            RayTraceResult entityTrace = player.getWorld().rayTraceEntities(
                    player.getEyeLocation(),
                    player.getEyeLocation().getDirection(),
                    4.5,
                    entity -> entity instanceof LivingEntity && !entity.getUniqueId().equals(uuid)
            );

            if (entityTrace != null && entityTrace.getHitEntity() != null) {
                UUID targetId = entityTrace.getHitEntity().getUniqueId();
                if (!targetId.equals(lastLookedTarget.get(uuid))) {
                    lastLookedTarget.put(uuid, targetId);
                    targetAcquiredTime.put(uuid, System.currentTimeMillis());
                }

                // Sabit robotik aci degisimi kontrolu (AimAssist)
                float prevYaw = lastYawDelta.getOrDefault(uuid, -1f);
                if (prevYaw > 0 && Math.abs(yawDiff - prevYaw) < 0.002f && yawDiff > 2.0f) {
                    int streak = aimAssistStreak.getOrDefault(uuid, 0) + 1;
                    aimAssistStreak.put(uuid, streak);
                    if (streak >= 5) {
                        aimAssistStreak.put(uuid, 0);
                        flagPlayer(player, "AimAssist", 2);
                    }
                } else {
                    aimAssistStreak.put(uuid, Math.max(0, aimAssistStreak.getOrDefault(uuid, 0) - 1));
                }
            } else {
                lastLookedTarget.remove(uuid);
            }
            lastYawDelta.put(uuid, yawDiff);
        }

        // --- FLY KONTROLU ---
        if (player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isGliding()
                || player.isRiptiding()
                || player.getAllowFlight()
                || player.isInsideVehicle()) {
            airTicks.put(uuid, 0);
            return;
        }

        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        if (!isSupported(player, to)) {
            int ticks = airTicks.getOrDefault(uuid, 0) + 1;
            airTicks.put(uuid, ticks);

            if (ticks > MAX_AIR_TICKS && to.getY() >= from.getY() - 0.02) {
                event.setTo(from);
                flagPlayer(player, "Fly", 2);
            }
        } else {
            airTicks.put(uuid, 0);
        }
    }

    // 2. REACH + DUVAR ARKASI / FREECAM VURUS + TRIGGERBOT KONTROLU
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombatCheck(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (attacker.hasPermission("antisolaris.bypass")) {
            return;
        }

        UUID uuid = attacker.getUniqueId();
        Entity target = event.getEntity();
        Location eyeLoc = attacker.getEyeLocation();
        Location targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0);

        // --- REACH KONTROLU ---
        double distance = eyeLoc.distance(targetCenter);
        if (distance > MAX_REACH) {
            event.setCancelled(true);
            flagPlayer(attacker, String.format("Reach (%.2f blok)", distance), 2);
            return;
        }

        // --- FREECAM / DUVAR ARKASI VURMA KONTROLU ---
        Vector direction = targetCenter.toVector().subtract(eyeLoc.toVector());
        double rayDistance = direction.length();
        if (rayDistance > 0.3) {
            RayTraceResult blockHit = attacker.getWorld().rayTraceBlocks(
                    eyeLoc,
                    direction.normalize(),
                    rayDistance,
                    FluidCollisionMode.NEVER,
                    true
            );

            if (blockHit != null && blockHit.getHitBlock() != null && blockHit.getHitBlock().getType().isOccluding()) {
                event.setCancelled(true);
                flagPlayer(attacker, "Freecam / Duvar Arkasi Vurus", 3);
                return;
            }
        }

        // --- TRIGGERBOT TEPKI SURESI KONTROLU ---
        Long acquired = targetAcquiredTime.get(uuid);
        if (acquired != null && target.getUniqueId().equals(lastLookedTarget.get(uuid))) {
            long reactionMs = System.currentTimeMillis() - acquired;
            if (reactionMs <= 20L) {
                int streak = fastTriggerStreak.getOrDefault(uuid, 0) + 1;
                fastTriggerStreak.put(uuid, streak);
                if (streak >= 4) {
                    fastTriggerStreak.put(uuid, 0);
                    event.setCancelled(true);
                    flagPlayer(attacker, "TriggerBot (" + reactionMs + "ms tepki)", 2);
                }
            } else {
                fastTriggerStreak.put(uuid, 0);
            }
            targetAcquiredTime.remove(uuid);
        }
    }

    // 3. XRAY KONTROLU
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onXrayCheck(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("antisolaris.bypass") || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long start = xrayWindowStart.getOrDefault(uuid, 0L);

        // Her 5 dakikada bir kazma istatistigini sifirla
        if (now - start > 300_000L) {
            xrayWindowStart.put(uuid, now);
            stoneMined.put(uuid, 0);
            valuableOresMined.put(uuid, 0);
        }

        Material type = event.getBlock().getType();
        if (type == Material.STONE || type == Material.DEEPSLATE || type == Material.NETHERRACK || type == Material.TUFF) {
            stoneMined.put(uuid, stoneMined.getOrDefault(uuid, 0) + 1);
        } else if (isValuableOre(type)) {
            int ores = valuableOresMined.getOrDefault(uuid, 0) + 1;
            int stones = stoneMined.getOrDefault(uuid, 0);
            valuableOresMined.put(uuid, ores);

            // Az tas kirip cok fazla degerli maden bulma orani
            if (ores >= 10 && stones < ores * 3) {
                valuableOresMined.put(uuid, 0);
                stoneMined.put(uuid, 0);
                flagPlayer(player, "Xray (Maden/Tas Orani)", 4);
            }
        }
    }

    private boolean isValuableOre(Material mat) {
        return mat == Material.DIAMOND_ORE
                || mat == Material.DEEPSLATE_DIAMOND_ORE
                || mat == Material.ANCIENT_DEBRIS
                || mat == Material.EMERALD_ORE
                || mat == Material.DEEPSLATE_EMERALD_ORE;
    }

    private boolean isSupported(Player player, Location loc) {
        if (player.isOnGround() || player.isInWater() || player.isClimbing()) {
            return true;
        }
        Material below = loc.clone().subtract(0, 0.25, 0).getBlock().getType();
        return below != Material.AIR && below != Material.CAVE_AIR && below != Material.VOID_AIR;
    }

    private float wrapAngle(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    // UYARI VE 3 GUNLUK OTOMATIK BAN SISTEMI
    private void flagPlayer(Player suspect, String cheatName, int vlAdd) {
        UUID uuid = suspect.getUniqueId();
        int totalVl = violations.getOrDefault(uuid, 0) + vlAdd;
        violations.put(uuid, totalVl);

        long now = System.currentTimeMillis();
        if (now - lastAlertTime.getOrDefault(uuid, 0L) >= 1000L) {
            lastAlertTime.put(uuid, now);
            String alertMsg = "§b[AntiSolaris] §f" + suspect.getName()
                    + " §7-> §c" + cheatName + " §8(VL: " + totalVl + "/" + BAN_THRESHOLD_VL + ")";

            Bukkit.getConsoleSender().sendMessage(alertMsg);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("antisolaris.alert")) {
                    online.sendMessage(alertMsg);
                }
            }
        }

        if (totalVl >= BAN_THRESHOLD_VL) {
            violations.remove(uuid);
            banForThreeDays(suspect, cheatName);
        }
    }

    private void banForThreeDays(Player suspect, String cheatName) {
        String reason = "§b[AntiSolaris] §cHile Korumasi Tarafindan Yasaklandiniz!\n"
                + "§7Sebep: §f" + cheatName + "\n"
                + "§7Sure: §e3 Gun\n"
                + "§7Yetkili: §bAntiSolaris";

        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        banList.addBan(suspect.getPlayerProfile(), reason, Duration.ofDays(3), "AntiSolaris");

        Bukkit.broadcastMessage("§b[AntiSolaris] §e" + suspect.getName()
                + " §7adli oyuncu §c" + cheatName + " §7nedeniyle §bAntiSolaris §7tarafindan §e3 gun §7yasaklandi!");

        suspect.kick(net.kyori.adventure.text.Component.text(reason));
    }
}
