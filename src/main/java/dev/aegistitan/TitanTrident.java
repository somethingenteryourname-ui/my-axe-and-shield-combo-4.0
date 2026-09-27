package dev.aegistitan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** The Leviathan Trident: throw it, ride a colossal trident to your target, and obliterate the landing zone. */
final class TitanTrident implements Listener {

    private final AegisTitan plugin;
    private final Items items;
    private final WallManager walls;
    private final Terrain terrain;
    private final NamespacedKey scaleKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> riders = new HashSet<>();
    private final List<Ride> rides = new ArrayList<>();
    private final Random random = new Random();

    TitanTrident(AegisTitan plugin, Items items, WallManager walls, Terrain terrain) {
        this.plugin = plugin;
        this.items = items;
        this.walls = walls;
        this.terrain = terrain;
        this.scaleKey = new NamespacedKey(plugin, "trident_scale");
    }

    // ------------------------------------------------------------------ size / cooldown

    double maxScale() {
        return Math.max(1.0, plugin.getConfig().getDouble("trident.max-scale", 10.0));
    }

    double getScale(Player p) {
        double s = p.getPersistentDataContainer().getOrDefault(scaleKey, PersistentDataType.DOUBLE, 1.0);
        return Math.max(1.0, Math.min(maxScale(), s));
    }

    double setScale(Player p, double scale) {
        double s = Math.max(1.0, Math.min(maxScale(), scale));
        p.getPersistentDataContainer().set(scaleKey, PersistentDataType.DOUBLE, s);
        return s;
    }

    int cooldownSeconds() {
        return Math.max(0, plugin.getConfig().getInt("trident.cooldown-seconds", 10));
    }

    void setCooldownSeconds(int seconds) {
        int s = Math.max(0, seconds);
        plugin.getConfig().set("trident.cooldown-seconds", s);
        plugin.saveConfig();
        long latest = System.currentTimeMillis() + s * 1000L;
        cooldowns.replaceAll((id, ready) -> Math.min(ready, latest));
    }

    /** Clean up when the server stops mid-ride. */
    void shutdown() {
        for (Ride ride : new ArrayList<>(rides)) {
            ride.cleanup();
        }
        rides.clear();
        riders.clear();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident) || !(trident.getShooter() instanceof Player player)) {
            return;
        }
        if (!items.isTrident(trident.getItemStack())) {
            return;
        }
        UUID id = player.getUniqueId();
        if (riders.contains(id)) {
            event.setCancelled(true);
            return;
        }
        if (player.isInsideVehicle()) {
            return; // just a normal throw
        }
        long now = System.currentTimeMillis();
        long ready = cooldowns.getOrDefault(id, 0L);
        if (now < ready) {
            player.sendActionBar(Component.text(String.format("Leviathan Ride recharging\u2026 %.1fs  (normal throw)",
                    (ready - now) / 1000.0), NamedTextColor.AQUA));
            return; // normal loyalty throw while recharging
        }
        event.setCancelled(true); // the trident stays in your hand; we summon the big one instead
        cooldowns.put(id, now + cooldownSeconds() * 1000L);
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        Ride ride = new Ride(player, getScale(player));
        rides.add(ride);
        ride.runTaskTimer(plugin, 0L, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player p && riders.contains(p.getUniqueId())) {
            event.setCancelled(true); // no jumping off mid-flight
        }
    }

    // ================================================================== the ride

    private final class Ride extends BukkitRunnable {

        private static final Vector UP = new Vector(0, 1, 0);

        // --- size
        private final double k;
        private final double shaftLen;
        private final double shaftR;
        private final double prongLen;
        private final double sideLen;
        private final double spread;
        private final double totalLen;
        private final double seatFromTip;
        private final double step;
        private final float volume;
        private final float pitch;

        private final Particle.DustOptions shaftDark;
        private final Particle.DustOptions shaftLight;
        private final Particle.DustOptions gold;
        private final Particle.DustOptions prong;
        private final Particle.DustOptions prongEdge;
        private final Particle.DustTransition gem;
        private final Particle.DustTransition tipGlow;
        private final Particle.DustOptions water;

        // --- timeline
        private final int summonTicks;
        private final int flightTicks;
        private static final int LINGER = 34;

        // --- path (the tip follows a curve from start to target)
        private final UUID riderId;
        private final World world;
        private final Vector p0;
        private final Vector p1;
        private final Vector p2;
        private final Vector startDir;
        private final Vector seat0;
        private final Vector flightDir0;
        private ItemDisplay vehicle;

        // --- live state
        private int t;
        private Vector tip;
        private Vector axis;
        private Vector prevTip;
        private int endedAt = -1;
        private boolean clashed;
        private Vector endPoint;
        private final Set<UUID> struck = new HashSet<>();

        // --- landing
        private Terrain.Scar scar;
        private final ArrayDeque<Block> holeQueue = new ArrayDeque<>();
        private final List<Vector> holeTops = new ArrayList<>();
        private BlockData groundData;
        private ItemStack chip;
        private double waveRadius;
        private double waveSpeed;
        private final Set<UUID> waveHit = new HashSet<>();

        Ride(Player player, double scale) {
            FileConfiguration cfg = plugin.getConfig();
            this.k = scale;
            double g = Math.pow(k, 0.8);
            this.shaftLen = 14 * g;
            this.shaftR = 0.3 * Math.pow(k, 0.6);
            this.prongLen = 4.2 * g;
            this.sideLen = 3.3 * g;
            this.spread = 2.2 * g;
            this.totalLen = shaftLen + prongLen;
            this.seatFromTip = shaftLen * 0.6 + prongLen;
            this.step = 0.3 * Math.pow(k, 0.5);
            this.volume = (float) (2.0 * Math.pow(k, 0.7));
            this.pitch = (float) (1.0 / Math.pow(k, 0.15));

            float size = (float) Math.min(4.0, 1.8 * Math.pow(k, 0.4));
            this.shaftDark = Fx.dust(0x1C5F63, size);
            this.shaftLight = Fx.dust(0x2E8C86, size);
            this.gold = Fx.dust(0xE3B341, size);
            this.prong = Fx.dust(0x6FE8D8, size);
            this.prongEdge = Fx.dust(0xD8FFF8, size * 0.9f);
            this.gem = Fx.fade(0x3FE0FF, 0xFFFFFF, Math.min(4f, size * 1.4f));
            this.tipGlow = Fx.fade(0xFFFFFF, 0x7FF7FF, size);
            this.water = Fx.dust(0x9BE7FF, size * 0.7f);

            this.riderId = player.getUniqueId();
            this.world = player.getWorld();
            this.summonTicks = 12 + (int) Math.round(k);

            // ---------- where are we going?
            Location eye = player.getEyeLocation();
            Vector origin = eye.toVector();
            Vector dir = eye.getDirection().normalize();
            double range = cfg.getDouble("trident.range", 90) * Math.pow(k, 0.35);
            Vector target;
            RayTraceResult rt = world.rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.6,
                    e -> e instanceof LivingEntity && !e.getUniqueId().equals(riderId));
            if (rt != null && rt.getHitEntity() != null) {
                target = rt.getHitEntity().getLocation().toVector();
            } else if (rt != null) {
                target = rt.getHitPosition();
            } else {
                target = groundBelow(origin.clone().add(dir.clone().multiply(range)));
            }
            if (target.distance(origin) < 10) {
                Vector flat = dir.clone().setY(0);
                if (flat.lengthSquared() < 1e-4) {
                    flat = new Vector(1, 0, 0);
                }
                target = groundBelow(origin.clone().add(flat.normalize().multiply(10)));
            }

            Vector a0 = dir.clone();
            if (a0.getY() < -0.3) {
                a0.setY(-0.3);
            }
            a0.normalize();
            this.startDir = a0;
            Vector seat = player.getLocation().toVector().add(new Vector(0, 0.6, 0));
            this.seat0 = seat.clone();
            Vector start = seat.clone().add(a0.clone().multiply(seatFromTip));
            this.p2 = target;
            double dist = start.distance(p2);
            this.p1 = start.clone().add(a0.clone().multiply(dist * 0.45)).add(UP.clone().multiply(dist * 0.12 + 2));
            // Line the start of the path up with the way the trident points when it launches
            Vector launchDir = a0.clone();
            for (int i = 0; i < 4; i++) {
                launchDir = p1.clone().subtract(start).normalize();
                start = seat.clone().add(launchDir.clone().multiply(seatFromTip));
            }
            this.p0 = start;
            this.flightDir0 = p1.clone().subtract(p0).normalize();

            double len = 0;
            Vector last = p0.clone();
            for (int i = 1; i <= 24; i++) {
                Vector p = bezier(i / 24.0);
                len += p.distance(last);
                last = p;
            }
            double speed = cfg.getDouble("trident.speed", 2.2) * Math.pow(k, 0.25);
            this.flightTicks = (int) Math.max(12, Math.min(160, Math.round(len / speed)));

            this.tip = p0.clone();
            this.prevTip = p0.clone();
            this.axis = a0.clone();

            // ---------- climb aboard
            Location seatLoc = seatWorld().toLocation(world, player.getLocation().getYaw(), player.getLocation().getPitch());
            this.vehicle = world.spawn(seatLoc, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setTeleportDuration(2);
            });
            vehicle.addPassenger(player);
            riders.add(riderId);
        }

        private Vector groundBelow(Vector target) {
            int x = (int) Math.floor(target.getX());
            int z = (int) Math.floor(target.getZ());
            int y = (int) Math.floor(target.getY());
            for (int i = 0; i < 160 && y > world.getMinHeight(); i++) {
                if (world.getBlockAt(x, y - 1, z).getType().isSolid()) {
                    break;
                }
                y--;
            }
            return new Vector(target.getX(), y, target.getZ());
        }

        private Vector bezier(double u) {
            double a = (1 - u) * (1 - u);
            double b = 2 * (1 - u) * u;
            double c = u * u;
            return new Vector(
                    a * p0.getX() + b * p1.getX() + c * p2.getX(),
                    a * p0.getY() + b * p1.getY() + c * p2.getY(),
                    a * p0.getZ() + b * p1.getZ() + c * p2.getZ());
        }

        private Vector bezierDir(double u) {
            Vector d = p1.clone().subtract(p0).multiply(2 * (1 - u)).add(p2.clone().subtract(p1).multiply(2 * u));
            return d.lengthSquared() < 1e-6 ? axis.clone() : d.normalize();
        }

        /** Where the rider sits: on top of the shaft. */
        private Vector seatWorld() {
            return tip.clone().subtract(axis.clone().multiply(seatFromTip)).add(new Vector(0, shaftR + 0.15, 0));
        }

        // ------------------------------------------------------------ main loop

        @Override
        public void run() {
            Player rider = Bukkit.getPlayer(riderId);
            if (endedAt < 0 && (rider == null || !rider.isValid() || !rider.getWorld().equals(world))) {
                cleanup();
                return;
            }

            if (endedAt < 0) {
                if (t < summonTicks) {
                    summonFrame(rider);
                } else {
                    flightFrame(rider);
                }
            } else {
                int since = t - endedAt;
                if (clashed) {
                    clashAftermath(since);
                    if (since > 26) {
                        finish();
                        return;
                    }
                } else {
                    landedAftermath(rider, since);
                    if (since > LINGER && since * waveSpeed > waveRadius + 1 && holeQueue.isEmpty()) {
                        finish();
                        return;
                    }
                }
            }
            t++;
        }

        private void summonFrame(Player rider) {
            double p = t / (double) summonTicks;
            // Swing round to point along the launch path while it forms
            double ease = p * p * (3 - 2 * p);
            axis = startDir.clone().multiply(1 - ease).add(flightDir0.clone().multiply(ease));
            if (axis.lengthSquared() < 1e-6) {
                axis = flightDir0.clone();
            }
            axis.normalize();
            tip = seat0.clone().add(axis.clone().multiply(seatFromTip));
            moveVehicle(rider);
            drawTrident(Math.min(1.0, 0.2 + p), 0);

            Vector center = tip.clone().subtract(axis.clone().multiply(totalLen * 0.5));
            for (int i = 0; i < 8; i++) {
                double ang = t * 0.5 + i * Math.PI / 4;
                double r = (4.0 * (1 - p) + 1.0) * Math.sqrt(k);
                Vector from = center.clone().add(new Vector(Math.cos(ang) * r, (random.nextDouble() - 0.5) * 2 * k, Math.sin(ang) * r));
                Fx.move(world, Particle.SPLASH, from, center.clone().subtract(from), 0.3);
                Fx.move(world, Particle.NAUTILUS, from, center.clone().subtract(from), 0.1);
            }
            Fx.spawn(world, Particle.GLOW, center, (int) (6 * Math.sqrt(k)), 1.5 * k, 0.05);
            if (t == 0) {
                Location loc = center.toLocation(world);
                world.playSound(loc, Sound.ITEM_TRIDENT_THUNDER, volume, 0.8f * pitch);
                world.playSound(loc, Sound.BLOCK_CONDUIT_ACTIVATE, volume, 0.7f * pitch);
                world.playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, volume * 0.6f, 0.6f);
            }
            if (t == summonTicks - 1) {
                Location loc = center.toLocation(world);
                world.playSound(loc, Sound.ITEM_TRIDENT_RIPTIDE_3, volume, 0.7f * pitch);
                world.playSound(loc, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, volume, 0.6f);
                Fx.spawn(world, Particle.SPLASH, center, (int) (80 * Math.sqrt(k)), 1.5 * k, 0.5);
                Fx.spawn(world, Particle.CLOUD, center, (int) (20 * Math.sqrt(k)), k, 0.1);
            }
        }

        private void flightFrame(Player rider) {
            int ft = t - summonTicks + 1;
            double p = Math.min(1.0, ft / (double) flightTicks);
            double u = 0.55 * p + 0.45 * p * p; // speeds up as it dives
            prevTip = tip.clone();
            tip = bezier(u);
            axis = bezierDir(u);
            Vector move = tip.clone().subtract(prevTip);
            double moved = move.length();

            // 1) Did it smash into an Aegis Wall?
            if (moved > 1e-4) {
                WallManager.WallHit hit = walls.rayCast(world, prevTip, move.clone().normalize(), moved + 0.2, riderId);
                if (hit != null) {
                    tip = hit.point().clone();
                    clash(rider, hit);
                    return;
                }
            }
            // 2) Did it hit the ground?
            int steps = (int) Math.ceil(moved / 0.5) + 1;
            for (int i = 1; i <= steps; i++) {
                Vector q = prevTip.clone().add(move.clone().multiply(i / (double) steps));
                if (q.getY() < world.getMinHeight()
                        || world.getBlockAt(q.getBlockX(), q.getBlockY(), q.getBlockZ()).getType().isSolid()) {
                    tip = q;
                    land(rider);
                    return;
                }
            }
            if (p >= 1.0) {
                land(rider);
                return;
            }

            moveVehicle(rider);
            drawTrident(1.0, t * 0.25);
            trail(rider);
            ramEntities(rider);
            if (ft % 8 == 0) {
                Location loc = tip.toLocation(world);
                world.playSound(loc, Sound.ITEM_TRIDENT_RIPTIDE_1, volume * 0.7f, 0.6f * pitch);
            }
        }

        private void moveVehicle(Player rider) {
            if (vehicle == null || !vehicle.isValid()) {
                return;
            }
            Location loc = seatWorld().toLocation(world, rider.getLocation().getYaw(), rider.getLocation().getPitch());
            vehicle.teleport(loc); // since 1.21.10 passengers stay on by default
            rider.setFallDistance(0);
        }

        private void trail(Player rider) {
            Vector butt = tip.clone().subtract(axis.clone().multiply(totalLen));
            Fx.spawn(world, Particle.CLOUD, butt, (int) Math.ceil(2 * Math.sqrt(k)), 0.3 * k, 0.02);
            Fx.spawn(world, Particle.SPLASH, butt, (int) (12 * Math.sqrt(k)), 0.4 * k, 0.2);
            Fx.spawn(world, Particle.ELECTRIC_SPARK, tip, 3, 0.2 * k, 0.1);
            // Spiral of water wrapping the shaft
            for (int i = 0; i < 10; i++) {
                double s = random.nextDouble() * totalLen;
                double ang = s * 0.9 + t * 0.6;
                Vector[] f = frame();
                double r = shaftR * 3 + 0.3 * k;
                Vector p = tip.clone().subtract(axis.clone().multiply(totalLen - s))
                        .add(f[0].clone().multiply(Math.cos(ang) * r)).add(f[1].clone().multiply(Math.sin(ang) * r));
                Fx.dust(world, p, water);
                if (i % 3 == 0) {
                    Fx.spawn(world, Particle.FALLING_WATER, p, 1, 0.05, 0);
                }
            }
        }

        /** Creatures the head plows through get swatted aside. */
        private void ramEntities(Player rider) {
            double dmg = plugin.getConfig().getDouble("trident.ram-damage", 10);
            double reach = spread + 1.0;
            for (Entity e : world.getNearbyEntities(tip.toLocation(world), reach, reach, reach)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(riderId) || struck.contains(e.getUniqueId())
                        || le.isDead() || isImmune(le)) {
                    continue;
                }
                struck.add(e.getUniqueId());
                if (dmg > 0) {
                    le.damage(dmg, rider);
                }
                Vector side = le.getLocation().toVector().subtract(tip).setY(0);
                if (side.lengthSquared() < 1e-4) {
                    side = new Vector(1, 0, 0);
                }
                le.setVelocity(side.normalize().multiply(1.2).add(axis.clone().multiply(0.8)).setY(0.6));
                Fx.spawn(world, Particle.CRIT, le.getLocation().toVector().add(new Vector(0, 1, 0)), 15, 0.4, 0.4);
            }
        }

        // ------------------------------------------------------------ landing

        private void land(Player rider) {
            endedAt = t;
            endPoint = tip.clone();
            dismount(rider, false);

            FileConfiguration cfg = plugin.getConfig();
            waveRadius = cfg.getDouble("trident.shockwave-radius", 12) * Math.pow(k, 0.85);
            waveSpeed = waveRadius / (12.0 + 2.0 * k);

            Block ground = Terrain.surface(world, tip.getX(), tip.getZ(), tip.getY());
            groundData = ground != null ? ground.getBlockData() : Material.STONE.createBlockData();
            Material chipType = groundData.getMaterial();
            chip = new ItemStack(chipType.isItem() && !chipType.isAir() ? chipType : Material.COBBLESTONE);

            Location loc = tip.toLocation(world);
            world.strikeLightningEffect(loc);
            if (k >= 3) {
                for (int i = 0; i < 3; i++) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    double r = waveRadius * (0.3 + random.nextDouble() * 0.4);
                    Vector p = groundBelow(tip.clone().add(new Vector(Math.cos(ang) * r, 4, Math.sin(ang) * r)));
                    world.strikeLightningEffect(p.toLocation(world));
                }
            }
            world.playSound(loc, Sound.ITEM_TRIDENT_THUNDER, volume * 1.5f, 0.6f * pitch);
            world.playSound(loc, Sound.ITEM_TRIDENT_HIT_GROUND, volume * 1.5f, 0.5f);
            world.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, volume * 1.5f, 0.6f * pitch);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, volume * 1.5f, 0.6f * pitch);
            world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, volume, 0.8f * pitch);
            world.playSound(loc, Sound.ENTITY_GENERIC_SPLASH, volume, 0.5f);

            Fx.spawn(world, Particle.EXPLOSION_EMITTER, tip, (int) Math.ceil(k), 0.8 * k, 0);
            Fx.spawn(world, Particle.SONIC_BOOM, tip, (int) Math.ceil(k), 0.5 * k, 0);
            Fx.spawn(world, Particle.BLOCK, tip, (int) (150 * Math.sqrt(k)), 1.5 * k, 0.4 * k, 1.5 * k, 0.4, groundData);
            burst(tip, 1.0, false);

            // Execute everything in the direct hit zone
            killZone(rider);

            // Four holes where the prongs punched in
            if (cfg.getBoolean("trident.holes", true)) {
                scar = terrain.newScar();
                planHoles();
            }
        }

        private void killZone(Player rider) {
            double kill = plugin.getConfig().getDouble("trident.kill-damage", 60);
            double zone = spread + 1.2 * Math.pow(k, 0.75) + 1.5;
            double yRange = 4 * Math.sqrt(k);
            for (Entity e : world.getNearbyEntities(tip.toLocation(world), zone, yRange, zone)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(riderId) || le.isDead() || isImmune(le)) {
                    continue;
                }
                WallManager.WallHit block = walls.blockingWall(le, tip.clone().add(new Vector(0, 1, 0)), riderId);
                if (block != null) {
                    walls.impact(block.wall(), block.point());
                    continue;
                }
                waveHit.add(e.getUniqueId());
                if (le.getHealth() > 1.0) {
                    le.setHealth(1.0);
                }
                le.setNoDamageTicks(0);
                if (rider != null) {
                    le.damage(kill, rider);
                } else {
                    le.damage(kill);
                }
                Fx.spawn(world, Particle.DAMAGE_INDICATOR, le.getLocation().toVector().add(new Vector(0, 1, 0)), 20, 0.4, 0.2);
            }
        }

        /** How far out from the shaft each side prong's point ends up. */
        private double sideTipRadial() {
            return spread * (1 + 0.25 * Math.sin(Math.PI * 0.8) - 0.35);
        }

        /** Radial directions of the three side prongs around the shaft. */
        private Vector[] prongDirections(Vector[] f) {
            Vector[] out = new Vector[3];
            for (int i = 0; i < 3; i++) {
                double ang = Math.toRadians(90 + i * 120);
                out[i] = f[0].clone().multiply(Math.cos(ang)).add(f[1].clone().multiply(Math.sin(ang)));
            }
            return out;
        }

        private void planHoles() {
            Vector[] f = frame();
            Vector[] radial = prongDirections(f);
            Vector down = axis.clone();
            if (down.getY() > -0.5) {
                down.setY(-0.5); // holes always go into the ground
                down.normalize();
            }
            double holeR = 0.9 * Math.pow(k, 0.75);
            double depth = 4 + 4 * Math.pow(k, 0.8);
            Vector collar = tip.clone().subtract(axis.clone().multiply(prongLen));

            List<Vector> starts = new ArrayList<>();
            starts.add(tip.clone()); // centre prong
            for (Vector r : radial) {
                Vector sideTip = collar.clone().add(r.clone().multiply(sideTipRadial())).add(axis.clone().multiply(sideLen));
                starts.add(sideTip);
            }
            Set<Block> seen = new HashSet<>();
            for (int h = 0; h < starts.size(); h++) {
                double r = h == 0 ? holeR * 1.25 : holeR;
                Vector start = starts.get(h).clone().subtract(down.clone().multiply(2 * r));
                Vector end = starts.get(h).clone().add(down.clone().multiply(depth));
                holeTops.add(starts.get(h).clone());
                int minX = (int) Math.floor(Math.min(start.getX(), end.getX()) - r);
                int maxX = (int) Math.floor(Math.max(start.getX(), end.getX()) + r);
                int minY = (int) Math.floor(Math.min(start.getY(), end.getY()) - r);
                int maxY = (int) Math.floor(Math.max(start.getY(), end.getY()) + r);
                int minZ = (int) Math.floor(Math.min(start.getZ(), end.getZ()) - r);
                int maxZ = (int) Math.floor(Math.max(start.getZ(), end.getZ()) + r);
                for (int y = maxY; y >= minY; y--) {
                    if (y <= world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Vector c = new Vector(x + 0.5, y + 0.5, z + 0.5);
                            if (Spike.distanceToSegment(c, start, end) <= r) {
                                Block b = world.getBlockAt(x, y, z);
                                if (seen.add(b)) {
                                    holeQueue.add(b);
                                }
                            }
                        }
                    }
                }
            }
            // Cracked, heaved-up ground around each hole
            for (Vector top : holeTops) {
                for (int i = 0; i < 18 * Math.sqrt(k); i++) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    double d = holeR + random.nextDouble() * holeR * 2.2;
                    Block b = Terrain.surface(world, top.getX() + Math.cos(ang) * d, top.getZ() + Math.sin(ang) * d, top.getY());
                    if (b != null && scar.crack(b, random) && random.nextDouble() < 0.3) {
                        scar.raise(b, random);
                    }
                }
            }
        }

        private void landedAftermath(Player rider, int since) {
            // The giant trident stays planted, crackling, then dissolves
            if (since <= LINGER) {
                drawTrident(1.0 - since / (double) LINGER, 0);
                if (since % 3 == 0) {
                    Vector top = tip.clone().subtract(axis.clone().multiply(totalLen));
                    Fx.spawn(world, Particle.ELECTRIC_SPARK, top, (int) (6 * Math.sqrt(k)), 0.4 * k, 0.2);
                }
                // Water geysers out of the holes
                if (since < 20) {
                    for (Vector top : holeTops) {
                        Fx.spawn(world, Particle.SPLASH, top, (int) (10 * Math.sqrt(k)), 0.4 * k, 0.6, 0.4 * k, 0.4, null);
                        Fx.move(world, Particle.CLOUD, top, new Vector(0, 1, 0), 0.15 * Math.sqrt(k));
                    }
                }
            }
            if (since >= 1 && since <= 5) {
                burst(endPoint, 0.35 / since, false);
            }
            digHoles(since);
            shockwave(rider, since);
        }

        private void digHoles(int since) {
            if (scar == null || holeQueue.isEmpty()) {
                return;
            }
            int budget = Math.max(200, plugin.getConfig().getInt("axe.blocks-per-tick", 6000));
            int n = 0;
            while (!holeQueue.isEmpty() && budget-- > 0) {
                Block b = holeQueue.poll();
                if (!world.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) {
                    continue;
                }
                BlockData data = b.getBlockData();
                if (scar.cut(b) && n++ % 12 == 0) {
                    Fx.spawn(world, Particle.BLOCK, b.getLocation().toVector().add(new Vector(0.5, 0.5, 0.5)),
                            4, 0.3, 0.3, 0.3, 0.1, data);
                }
            }
        }

        private void shockwave(Player rider, int since) {
            double r = since * waveSpeed;
            if (r > waveRadius) {
                return;
            }
            double spacing = 0.6 * Math.sqrt(k);
            int n = (int) (2 * Math.PI * r / spacing) + 8;
            for (int i = 0; i < n; i++) {
                double a = i * 2 * Math.PI / n;
                double x = endPoint.getX() + Math.cos(a) * r;
                double z = endPoint.getZ() + Math.sin(a) * r;
                Block top = Terrain.surface(world, x, z, endPoint.getY());
                if (top == null) {
                    continue;
                }
                Vector p = new Vector(x, top.getY() + 1.05, z);
                Vector outward = new Vector(Math.cos(a), 0.7 + random.nextDouble() * 0.5, Math.sin(a));
                Fx.spawn(world, Particle.BLOCK, p, 2, 0.15 * k, 0.05, 0.15 * k, 0.1, top.getBlockData());
                Fx.dust(world, p.clone().add(new Vector(0, 0.3, 0)), water);
                Fx.move(world, Particle.ITEM, p, outward, (0.18 + random.nextDouble() * 0.2) * Math.sqrt(k), chip);
                if (i % 2 == 0) {
                    Fx.move(world, Particle.SPLASH, p, outward, 0.5);
                }
                if (i % 4 == 0) {
                    Fx.spawn(world, Particle.ELECTRIC_SPARK, p, 1, 0.1, 0.05);
                }
                if (i % 6 == 0) {
                    Fx.spawn(world, Particle.SWEEP_ATTACK, p.clone().add(new Vector(0, 0.4, 0)), 1, 0, 0);
                }
            }
            if (since % 3 == 0) {
                world.playSound(endPoint.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, volume * 0.4f,
                        0.45f + (float) (r / waveRadius) * 0.3f);
            }

            FileConfiguration cfg = plugin.getConfig();
            double maxDamage = cfg.getDouble("trident.max-damage", 14);
            double minDamage = cfg.getDouble("trident.min-damage", 5);
            double knockback = cfg.getDouble("trident.knockback", 1.8) * Math.sqrt(k);
            double yRange = 5 * Math.sqrt(k);
            for (Entity e : world.getNearbyEntities(endPoint.toLocation(world), waveRadius + 1, yRange + 1, waveRadius + 1)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(riderId) || waveHit.contains(e.getUniqueId())
                        || le.isDead() || isImmune(le)) {
                    continue;
                }
                Vector pos = le.getLocation().toVector();
                double dist = Math.hypot(pos.getX() - endPoint.getX(), pos.getZ() - endPoint.getZ());
                if (dist > r || Math.abs(pos.getY() - endPoint.getY()) > yRange) {
                    continue;
                }
                waveHit.add(e.getUniqueId());
                WallManager.WallHit block = walls.blockingWall(le, endPoint.clone().add(new Vector(0, 1, 0)), riderId);
                if (block != null) {
                    walls.impact(block.wall(), block.point());
                    continue;
                }
                double damage = maxDamage - (maxDamage - minDamage) * (dist / Math.max(0.1, waveRadius));
                if (rider != null) {
                    le.damage(damage, rider);
                } else {
                    le.damage(damage);
                }
                Vector push = pos.clone().subtract(endPoint).setY(0);
                if (push.lengthSquared() < 1e-4) {
                    push = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                }
                push.normalize().multiply(knockback * (1 - 0.5 * dist / Math.max(0.1, waveRadius)));
                push.setY(Math.min(1.5, 0.55 * Math.sqrt(k)));
                le.setVelocity(push);
            }
        }

        // ------------------------------------------------------------ clash with an Aegis Wall

        private void clash(Player rider, WallManager.WallHit hit) {
            endedAt = t;
            clashed = true;
            endPoint = hit.point().clone();
            Wall wall = hit.wall();
            FileConfiguration cfg = plugin.getConfig();

            // Rider is thrown back off the shattered trident
            dismount(rider, true);

            // Wall holder is shoved back but safe
            Player owner = Bukkit.getPlayer(wall.owner);
            if (owner != null) {
                owner.setVelocity(wall.normal.clone().multiply(-0.9).setY(0.35));
            }
            for (int i = 0; i < 4; i++) {
                walls.impact(wall, endPoint);
            }

            Location loc = endPoint.toLocation(world);
            world.playSound(loc, Sound.ITEM_TRIDENT_THUNDER, volume * 1.5f, 0.7f);
            world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, volume * 1.5f, 0.6f);
            world.playSound(loc, Sound.BLOCK_ANVIL_LAND, volume * 1.2f, 0.5f);
            world.playSound(loc, Sound.ITEM_SHIELD_BLOCK, volume * 1.5f, 0.5f);
            world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, volume, 0.9f);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, volume * 1.5f, 0.7f);
            world.playSound(loc, Sound.ITEM_TRIDENT_HIT, volume, 0.5f);

            BlockData rock = Material.PRISMARINE.createBlockData();
            ItemStack shard = new ItemStack(Material.PRISMARINE_SHARD);
            groundData = rock;
            chip = shard;

            Fx.spawn(world, Particle.EXPLOSION_EMITTER, endPoint, (int) Math.ceil(k), 0.5 * k, 0);
            Fx.spawn(world, Particle.SONIC_BOOM, endPoint, (int) Math.ceil(2 * k), 0.8 * k, 0);
            Fx.spawn(world, Particle.FLASH, endPoint, 1, 0, 0, 0, 0, org.bukkit.Color.WHITE);
            burst(endPoint, 1.4, true);

            // Air shockwave shoves everything nearby
            double airRadius = 8 * Math.pow(k, 0.7);
            double clashDamage = cfg.getDouble("trident.clash-damage", 8);
            for (Entity e : world.getNearbyEntities(loc, airRadius, airRadius, airRadius)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(riderId) || e.getUniqueId().equals(wall.owner)
                        || le.isDead() || isImmune(le)) {
                    continue;
                }
                Vector away = le.getLocation().toVector().subtract(endPoint);
                double dist = away.length();
                if (dist > airRadius) {
                    continue;
                }
                if (away.lengthSquared() < 1e-4) {
                    away = new Vector(0, 1, 0);
                }
                if (clashDamage > 0) {
                    if (rider != null) {
                        le.damage(clashDamage, rider);
                    } else {
                        le.damage(clashDamage);
                    }
                }
                le.setVelocity(away.normalize().multiply(1.6 * (1 - 0.5 * dist / airRadius)).setY(0.6));
            }

            // Spikes shoot out of the collision
            int count = Math.max(0, cfg.getInt("trident.clash-spikes", 10));
            double spikeDamage = cfg.getDouble("axe.spike-damage", 6);
            if (count > 0) {
                scar = terrain.newScar();
            }
            for (int i = 0; i < count; i++) {
                double ang = i * Math.PI * 2 / count + random.nextDouble() * 0.4;
                Vector d = wall.right.clone().multiply(Math.cos(ang)).add(wall.up.clone().multiply(Math.sin(ang)))
                        .add(wall.normal.clone().multiply((random.nextDouble() - 0.5) * 0.9));
                d.setY(d.getY() + 0.35);
                d.normalize().multiply((0.9 + random.nextDouble() * 0.6) * Math.sqrt(k));
                new Spike(walls, random, world, endPoint.clone(), d, k, riderId, scar,
                        plugin.getConfig().getBoolean("axe.crater", true), shard, rock, spikeDamage, volume)
                        .runTaskTimer(plugin, 1L, 1L);
            }
        }

        private void clashAftermath(int since) {
            // The trident shatters apart
            if (since <= 18) {
                drawShattered(since);
            }
            // Rings of force blasting outward in the air
            if (since <= 14) {
                double r = since * 0.9 * Math.pow(k, 0.7);
                Vector[] f = frame();
                int n = (int) (2 * Math.PI * r / (0.5 * Math.sqrt(k))) + 10;
                for (int i = 0; i < n; i++) {
                    double a = i * 2 * Math.PI / n;
                    Vector ringA = endPoint.clone().add(f[0].clone().multiply(Math.cos(a) * r)).add(f[1].clone().multiply(Math.sin(a) * r));
                    Vector ringB = endPoint.clone().add(new Vector(Math.cos(a) * r * 0.8, 0, Math.sin(a) * r * 0.8));
                    Fx.dust(world, ringA, since < 5 ? prongEdge : water);
                    if (i % 2 == 0) {
                        Fx.spawn(world, Particle.CLOUD, ringB, 1, 0.1, 0.02);
                    }
                    if (i % 5 == 0) {
                        Fx.spawn(world, Particle.ELECTRIC_SPARK, ringA, 1, 0.05, 0.05);
                        Fx.spawn(world, Particle.SWEEP_ATTACK, ringB, 1, 0, 0);
                    }
                }
                if (since % 3 == 0) {
                    world.playSound(endPoint.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, volume * 0.5f, 0.9f);
                }
            }
            if (since >= 1 && since <= 6) {
                burst(endPoint, 0.5 / since, true);
            }
        }

        private void dismount(Player rider, boolean thrownBack) {
            riders.remove(riderId);
            if (vehicle != null && vehicle.isValid()) {
                vehicle.eject();
                vehicle.remove();
            }
            vehicle = null;
            if (rider == null) {
                return;
            }
            rider.setFallDistance(0);
            if (thrownBack) {
                Vector back = axis.clone().multiply(-1.3).setY(0.7);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    rider.setVelocity(back);
                    rider.setFallDistance(0);
                });
                rider.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, false, false, true));
            } else {
                // Hop off just behind the impact
                Vector flat = axis.clone().setY(0);
                if (flat.lengthSquared() < 1e-4) {
                    flat = new Vector(1, 0, 0);
                }
                flat.normalize();
                Vector spot = groundBelow(tip.clone().subtract(flat.multiply(spread + 3.5 * Math.pow(k, 0.6)))
                        .add(new Vector(0, 3, 0)));
                Location to = spot.toLocation(world, rider.getLocation().getYaw(), rider.getLocation().getPitch());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    rider.teleport(to);
                    rider.setFallDistance(0);
                    rider.setVelocity(new Vector(0, 0.3, 0));
                });
            }
        }

        private void finish() {
            if (scar != null) {
                scar.finish();
            }
            cancel();
            rides.remove(this);
        }

        void cleanup() {
            riders.remove(riderId);
            if (vehicle != null && vehicle.isValid()) {
                vehicle.eject();
                vehicle.remove();
            }
            vehicle = null;
            if (scar != null) {
                scar.finish();
                scar = null;
            }
            try {
                cancel();
            } catch (IllegalStateException ignored) {
                // not scheduled yet
            }
            rides.remove(this);
        }

        private boolean isImmune(LivingEntity le) {
            return le instanceof Player pl && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR);
        }

        // ------------------------------------------------------------ particles

        /** Two unit vectors perpendicular to the trident: [0] = sideways, [1] = "up" relative to it. */
        private Vector[] frame() {
            Vector side = axis.getCrossProduct(UP);
            if (side.lengthSquared() < 1e-4) {
                side = new Vector(1, 0, 0);
            }
            side.normalize();
            Vector up2 = side.getCrossProduct(axis).normalize();
            return new Vector[]{side, up2};
        }

        /** Huge spray of water, sparks, chips and glow flying out of a point. */
        private void burst(Vector at, double amount, boolean sphere) {
            int n = (int) (360 * Math.sqrt(k) * amount);
            double sk = Math.sqrt(k);
            for (int i = 0; i < n; i++) {
                double yaw = random.nextDouble() * Math.PI * 2;
                double elev = sphere ? Math.asin(random.nextDouble() * 2 - 1) : Math.toRadians(4 + random.nextDouble() * 62);
                Vector d = new Vector(Math.cos(yaw) * Math.cos(elev), Math.sin(elev), Math.sin(yaw) * Math.cos(elev));
                Vector from = at.clone().add(new Vector((random.nextDouble() - 0.5) * k, random.nextDouble() * 0.5 * k,
                        (random.nextDouble() - 0.5) * k));
                double speed = (0.3 + random.nextDouble() * 0.9) * sk;
                double r = random.nextDouble();
                if (r < 0.3 && chip != null) {
                    Fx.move(world, Particle.ITEM, from, d, speed * 0.9, chip);
                } else if (r < 0.5) {
                    Fx.move(world, Particle.SPLASH, from, d, speed * 1.2);
                } else if (r < 0.62) {
                    Fx.move(world, Particle.ELECTRIC_SPARK, from, d, speed);
                } else if (r < 0.72) {
                    Fx.move(world, Particle.GLOW, from, d, speed * 0.5);
                } else if (r < 0.82) {
                    Fx.move(world, Particle.FIREWORK, from, d, speed * 0.45);
                } else if (r < 0.9) {
                    Fx.move(world, Particle.CLOUD, from, d, speed * 0.3);
                } else if (r < 0.96) {
                    Fx.move(world, Particle.CRIT, from, d, speed);
                } else {
                    Fx.move(world, Particle.END_ROD, from, d, speed * 0.4);
                }
            }
        }

        /** The giant trident: shaft, gold bands, glowing gem, centre prong and three barbed side prongs. */
        private void drawTrident(double visibility, double spin) {
            Vector[] f = frame();
            Vector side = f[0];
            Vector up2 = f[1];
            Vector butt = tip.clone().subtract(axis.clone().multiply(totalLen));
            int ring = k < 2 ? 1 : (k < 5 ? 4 : 6);
            double band = 2.5 * Math.pow(k, 0.8);

            // Shaft
            for (double s = 0; s <= shaftLen; s += step) {
                boolean isGold = s < step * 2 || (s > shaftLen - band * 1.5 && ((int) (s / (band * 0.4))) % 2 == 0);
                for (int i = 0; i < ring; i++) {
                    if (visibility < 1 && random.nextDouble() > visibility) {
                        continue;
                    }
                    double ang = spin + i * Math.PI * 2 / ring;
                    double rr = ring == 1 ? 0 : shaftR;
                    Vector p = at(butt, s, side, up2, Math.cos(ang) * rr, Math.sin(ang) * rr);
                    Fx.dust(world, p, isGold ? gold : (i % 2 == 0 ? shaftDark : shaftLight));
                }
            }

            // Collar (gold ring) with the glowing gem in the middle
            Vector collar = butt.clone().add(axis.clone().multiply(shaftLen));
            int collarPts = (int) Math.max(12, 2 * Math.PI * spread / step);
            for (int i = 0; i < collarPts; i++) {
                if (visibility < 1 && random.nextDouble() > visibility) {
                    continue;
                }
                double ang = i * Math.PI * 2 / collarPts;
                double rr = spread * 0.55;
                Fx.dust(world, collar.clone().add(side.clone().multiply(Math.cos(ang) * rr)).add(up2.clone().multiply(Math.sin(ang) * rr)), gold);
            }
            if (visibility >= 1 || random.nextDouble() < visibility) {
                Fx.fade(world, collar, gem);
                Fx.spawn(world, Particle.GLOW, collar, 1, 0.2 * k, 0);
            }

            // Centre prong (tapers to a white-hot point)
            for (double s = 0; s <= prongLen; s += step * 0.8) {
                if (visibility < 1 && random.nextDouble() > visibility) {
                    continue;
                }
                double f2 = s / prongLen;
                double rr = shaftR * 1.3 * (1 - f2);
                Vector p = collar.clone().add(axis.clone().multiply(s));
                if (f2 > 0.8) {
                    Fx.fade(world, p, tipGlow);
                } else {
                    Fx.dust(world, p, prong);
                    if (ring > 1) {
                        Fx.dust(world, p.clone().add(side.clone().multiply(rr)), prongEdge);
                        Fx.dust(world, p.clone().add(side.clone().multiply(-rr)), prongEdge);
                    }
                }
            }

            // Three side prongs: out from the collar, sweeping forward, with a barb at each tip
            Vector[] radial = prongDirections(f);
            for (Vector r : radial) {
                // arm from shaft to prong base
                for (double d = 0; d <= spread; d += step) {
                    if (visibility < 1 && random.nextDouble() > visibility) {
                        continue;
                    }
                    Vector p = collar.clone().add(r.clone().multiply(d)).subtract(axis.clone().multiply(0.4 * k * Math.sin(Math.PI * d / spread)));
                    Fx.dust(world, p, gold);
                }
                // prong itself
                Vector base = collar.clone().add(r.clone().multiply(spread));
                for (double s = 0; s <= sideLen; s += step * 0.8) {
                    if (visibility < 1 && random.nextDouble() > visibility) {
                        continue;
                    }
                    double f2 = s / sideLen;
                    double bulge = 0.25 * spread * Math.sin(Math.PI * f2 * 0.8);
                    Vector p = base.clone().add(axis.clone().multiply(s)).add(r.clone().multiply(bulge - 0.35 * spread * f2 * f2));
                    Fx.dust(world, p, f2 > 0.85 ? prongEdge : prong);
                }
                // barb
                Vector sideTip = collar.clone().add(r.clone().multiply(sideTipRadial())).add(axis.clone().multiply(sideLen));
                for (double s = 0; s <= 0.9 * Math.pow(k, 0.8); s += step) {
                    if (visibility < 1 && random.nextDouble() > visibility) {
                        continue;
                    }
                    Vector p = sideTip.clone().subtract(axis.clone().multiply(s)).add(r.clone().multiply(s * 0.6));
                    Fx.dust(world, p, prongEdge);
                }
                if (visibility >= 1 || random.nextDouble() < visibility) {
                    Fx.fade(world, sideTip, tipGlow);
                }
            }
            if (visibility >= 1 || random.nextDouble() < visibility) {
                Fx.spawn(world, Particle.END_ROD, tip, 1, 0, 0);
            }
        }

        /** The trident blowing apart after hitting a wall. */
        private void drawShattered(int since) {
            double spreadOut = since * 0.35 * Math.sqrt(k);
            double visibility = 1.0 - since / 18.0;
            Vector butt = tip.clone().subtract(axis.clone().multiply(totalLen));
            for (double s = 0; s <= totalLen; s += step * 1.5) {
                if (random.nextDouble() > visibility) {
                    continue;
                }
                Vector p = butt.clone().add(axis.clone().multiply(s));
                Vector away = p.clone().subtract(endPoint);
                if (away.lengthSquared() < 1e-4) {
                    away = new Vector(0, 1, 0);
                }
                away.normalize().multiply(spreadOut * (0.5 + random.nextDouble()));
                away.setY(away.getY() - 0.02 * since * since);
                p.add(away);
                Fx.dust(world, p, s > shaftLen ? prong : (random.nextBoolean() ? shaftDark : gold));
                if (random.nextInt(6) == 0) {
                    Fx.spawn(world, Particle.ELECTRIC_SPARK, p, 1, 0.1, 0.05);
                }
            }
        }

        private Vector at(Vector butt, double s, Vector side, Vector up2, double x, double y) {
            return new Vector(
                    butt.getX() + axis.getX() * s + side.getX() * x + up2.getX() * y,
                    butt.getY() + axis.getY() * s + side.getY() * x + up2.getY() * y,
                    butt.getZ() + axis.getZ() * s + side.getZ() * x + up2.getZ() * y);
        }
    }
}
