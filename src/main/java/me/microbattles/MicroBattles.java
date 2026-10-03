package me.microbattles;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Micro Battles: two teams (red/blue) fight in a small arena, last team standing wins.
 * Built for Spigot/Bukkit 1.8.8. The arena world and map are generated automatically.
 */
public class MicroBattles extends JavaPlugin implements Listener {

    private enum State { WAITING, COUNTDOWN, RUNNING, ENDING }

    private static final String PREFIX = ChatColor.GOLD + "[MicroBattles] " + ChatColor.GRAY;
    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 8;
    private static final int COUNTDOWN_SECONDS = 15;
    private static final int GAME_SECONDS = 300;
    private static final int BASE_Y = 64;
    private static final int LOBBY_Y = 100;
    private static final int HALF = 15;
    private static final List<String> KITS = Arrays.asList("warrior", "archer", "tank");

    private World world;
    private State state = State.WAITING;

    private final Set<UUID> players = new LinkedHashSet<UUID>();
    private final Set<UUID> inGame = new HashSet<UUID>();
    private final Set<UUID> alive = new HashSet<UUID>();
    private final Map<UUID, Integer> team = new HashMap<UUID, Integer>();
    private final Map<UUID, String> kit = new HashMap<UUID, String>();

    private final Map<UUID, ItemStack[]> savedInv = new HashMap<UUID, ItemStack[]>();
    private final Map<UUID, ItemStack[]> savedArmor = new HashMap<UUID, ItemStack[]>();
    private final Map<UUID, GameMode> savedMode = new HashMap<UUID, GameMode>();
    private final Map<UUID, Location> savedLoc = new HashMap<UUID, Location>();

    private BukkitTask countdownTask;
    private BukkitTask gameTask;

    // ------------------------------------------------------------------ world

    public static class VoidGen extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World w, Random r, int x, int z, BiomeGrid biomes) {
            return createChunkData(w);
        }

        @Override
        public Location getFixedSpawnLocation(World w, Random r) {
            return new Location(w, 0.5, LOBBY_Y, 0.5);
        }
    }

    @Override
    public void onEnable() {
        world = new WorldCreator("MicroBattles").generator(new VoidGen()).createWorld();
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doMobSpawning", "false");
        world.setTime(6000);
        world.setStorm(false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setSpawnFlags(false, false);
        world.setSpawnLocation(0, LOBBY_Y, 0);

        buildLobby();
        buildArena();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MicroBattles enabled - arena built in world 'MicroBattles'.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<UUID>(players)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                leave(p);
            }
        }
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m, int data) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    world.getBlockAt(x, y, z).setTypeIdAndData(m.getId(), (byte) data, false);
                }
            }
        }
    }

    private void buildLobby() {
        fill(-8, LOBBY_Y, -8, 8, LOBBY_Y + 6, 8, Material.AIR, 0);
        fill(-7, LOBBY_Y - 1, -7, 7, LOBBY_Y - 1, 7, Material.QUARTZ_BLOCK, 0);
        // invisible walls + ceiling so nobody falls off
        fill(-7, LOBBY_Y, -7, 7, LOBBY_Y + 3, -7, Material.BARRIER, 0);
        fill(-7, LOBBY_Y, 7, 7, LOBBY_Y + 3, 7, Material.BARRIER, 0);
        fill(-7, LOBBY_Y, -7, -7, LOBBY_Y + 3, 7, Material.BARRIER, 0);
        fill(7, LOBBY_Y, -7, 7, LOBBY_Y + 3, 7, Material.BARRIER, 0);
        fill(-7, LOBBY_Y + 4, -7, 7, LOBBY_Y + 4, 7, Material.BARRIER, 0);
        // little decoration
        fill(-1, LOBBY_Y - 1, -1, 1, LOBBY_Y - 1, 1, Material.GOLD_BLOCK, 0);
    }

    /** (Re)builds the whole arena. Called at startup and before every game. */
    private void buildArena() {
        // wipe
        fill(-HALF - 1, BASE_Y - 3, -HALF - 1, HALF + 1, BASE_Y + 12, HALF + 1, Material.AIR, 0);
        // ground
        fill(-HALF, BASE_Y - 2, -HALF, HALF, BASE_Y - 2, HALF, Material.STONE, 0);
        fill(-HALF, BASE_Y - 1, -HALF, HALF, BASE_Y - 1, HALF, Material.DIRT, 0);
        fill(-HALF, BASE_Y, -HALF, HALF, BASE_Y, HALF, Material.GRASS, 0);
        // outer walls
        fill(-HALF, BASE_Y + 1, -HALF, HALF, BASE_Y + 8, -HALF, Material.SMOOTH_BRICK, 0);
        fill(-HALF, BASE_Y + 1, HALF, HALF, BASE_Y + 8, HALF, Material.SMOOTH_BRICK, 0);
        fill(-HALF, BASE_Y + 1, -HALF, -HALF, BASE_Y + 8, HALF, Material.SMOOTH_BRICK, 0);
        fill(HALF, BASE_Y + 1, -HALF, HALF, BASE_Y + 8, HALF, Material.SMOOTH_BRICK, 0);
        // invisible ceiling
        fill(-HALF, BASE_Y + 9, -HALF, HALF, BASE_Y + 9, HALF, Material.BARRIER, 0);
        // team pads (red west, blue east)
        fill(-14, BASE_Y, -4, -10, BASE_Y, 4, Material.WOOL, 14);
        fill(10, BASE_Y, -4, 14, BASE_Y, 4, Material.WOOL, 11);
        // corner pillars
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int x0 = sx * 7;
                int z0 = sz * 7;
                fill(x0, BASE_Y + 1, z0, x0 + 1, BASE_Y + 4, z0 + 1, Material.LOG, 0);
            }
        }
        // center block
        fill(-1, BASE_Y + 1, -1, 1, BASE_Y + 3, 1, Material.HARD_CLAY, 0);
        // low walls for cover
        fill(-3, BASE_Y + 1, -6, -3, BASE_Y + 2, -4, Material.COBBLESTONE, 0);
        fill(3, BASE_Y + 1, -6, 3, BASE_Y + 2, -4, Material.COBBLESTONE, 0);
        fill(-3, BASE_Y + 1, 4, -3, BASE_Y + 2, 6, Material.COBBLESTONE, 0);
        fill(3, BASE_Y + 1, 4, 3, BASE_Y + 2, 6, Material.COBBLESTONE, 0);
    }

    private Location lobbySpawn() {
        return new Location(world, 0.5, LOBBY_Y, 0.5);
    }

    private Location teamSpawn(int teamId, int slot) {
        double z = -3 + slot * 2 + 0.5;
        double x = teamId == 0 ? -12.5 : 12.5;
        float yaw = teamId == 0 ? -90f : 90f;
        return new Location(world, x, BASE_Y + 1, z, yaw, 0f);
    }

    // ------------------------------------------------------------------ helpers

    private void tell(String msg) {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(PREFIX + msg);
            }
        }
    }

    private void resetPlayer(Player p, GameMode mode) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        p.setGameMode(mode);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
    }

    private void saveState(Player p) {
        UUID id = p.getUniqueId();
        if (savedInv.containsKey(id)) {
            return;
        }
        savedInv.put(id, p.getInventory().getContents().clone());
        savedArmor.put(id, p.getInventory().getArmorContents().clone());
        savedMode.put(id, p.getGameMode());
        savedLoc.put(id, p.getLocation());
    }

    private void restoreState(Player p) {
        UUID id = p.getUniqueId();
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        if (savedInv.containsKey(id)) {
            p.getInventory().setContents(savedInv.remove(id));
            p.getInventory().setArmorContents(savedArmor.remove(id));
        }
        GameMode gm = savedMode.remove(id);
        if (gm != null) {
            p.setGameMode(gm);
        }
        Location back = savedLoc.remove(id);
        if (back == null || back.getWorld() == null || back.getWorld().equals(world)) {
            back = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        p.teleport(back);
    }

    private ItemStack leather(Material m, Color c) {
        ItemStack item = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(c);
        item.setItemMeta(meta);
        return item;
    }

    private void giveKit(Player p, int teamId) {
        PlayerInventory inv = p.getInventory();
        Color c = teamId == 0 ? Color.RED : Color.BLUE;
        String k = kit.containsKey(p.getUniqueId()) ? kit.get(p.getUniqueId()) : "warrior";

        inv.setHelmet(leather(Material.LEATHER_HELMET, c));
        inv.setChestplate(leather(Material.LEATHER_CHESTPLATE, c));
        inv.setLeggings(leather(Material.LEATHER_LEGGINGS, c));
        inv.setBoots(leather(Material.LEATHER_BOOTS, c));

        if (k.equals("archer")) {
            inv.addItem(new ItemStack(Material.STONE_SWORD), new ItemStack(Material.BOW),
                    new ItemStack(Material.ARROW, 32), new ItemStack(Material.GOLDEN_APPLE, 1));
        } else if (k.equals("tank")) {
            inv.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            inv.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
            inv.addItem(new ItemStack(Material.STONE_SWORD), new ItemStack(Material.GOLDEN_APPLE, 3));
        } else {
            inv.addItem(new ItemStack(Material.IRON_SWORD), new ItemStack(Material.GOLDEN_APPLE, 2));
        }
    }

    // ------------------------------------------------------------------ game flow

    private void join(Player p) {
        UUID id = p.getUniqueId();
        if (players.contains(id)) {
            p.sendMessage(PREFIX + "You are already in the queue.");
            return;
        }
        saveState(p);
        players.add(id);
        if (!kit.containsKey(id)) {
            kit.put(id, "warrior");
        }
        resetPlayer(p, GameMode.ADVENTURE);
        p.teleport(lobbySpawn());
        tell(ChatColor.YELLOW + p.getName() + ChatColor.GRAY + " joined (" + players.size() + "/" + MAX_PLAYERS + ").");
        if (state == State.RUNNING || state == State.ENDING) {
            p.sendMessage(PREFIX + "A game is running. You'll play in the next one.");
        }
        p.sendMessage(PREFIX + "Pick a kit with " + ChatColor.AQUA + "/mb kit <warrior|archer|tank>");
        tryCountdown();
    }

    private void leave(Player p) {
        UUID id = p.getUniqueId();
        boolean was = players.remove(id);
        if (!was) {
            if (p.getWorld().equals(world)) {
                p.setGameMode(GameMode.SURVIVAL);
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
            return;
        }
        inGame.remove(id);
        boolean wasAlive = alive.remove(id);
        team.remove(id);
        restoreState(p);
        if (wasAlive && state == State.RUNNING) {
            tell(ChatColor.YELLOW + p.getName() + ChatColor.GRAY + " left the game.");
            checkWin();
        }
        if (state == State.COUNTDOWN && players.size() < MIN_PLAYERS) {
            cancelCountdown();
        }
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (state == State.COUNTDOWN) {
            state = State.WAITING;
            tell("Not enough players, countdown cancelled.");
        }
    }

    private void tryCountdown() {
        if (state != State.WAITING || players.size() < MIN_PLAYERS) {
            return;
        }
        state = State.COUNTDOWN;
        countdownTask = new BukkitRunnable() {
            int t = COUNTDOWN_SECONDS;

            @Override
            public void run() {
                if (players.size() < MIN_PLAYERS) {
                    countdownTask = null;
                    state = State.WAITING;
                    tell("Not enough players, countdown cancelled.");
                    cancel();
                    return;
                }
                if (t <= 0) {
                    countdownTask = null;
                    cancel();
                    startGame();
                    return;
                }
                if (t == 15 || t == 10 || t <= 5) {
                    tell("Starting in " + ChatColor.GREEN + t + ChatColor.GRAY + "s...");
                }
                t--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void startGame() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        buildArena();
        inGame.clear();
        alive.clear();
        team.clear();

        List<UUID> list = new ArrayList<UUID>();
        for (UUID id : players) {
            if (Bukkit.getPlayer(id) != null) {
                list.add(id);
            }
        }
        Collections.shuffle(list);
        if (list.size() > MAX_PLAYERS) {
            list = new ArrayList<UUID>(list.subList(0, MAX_PLAYERS));
        }

        int[] slots = new int[] { 0, 0 };
        for (int i = 0; i < list.size(); i++) {
            UUID id = list.get(i);
            Player p = Bukkit.getPlayer(id);
            int t = i % 2;
            team.put(id, t);
            inGame.add(id);
            alive.add(id);
            resetPlayer(p, GameMode.SURVIVAL);
            giveKit(p, t);
            p.teleport(teamSpawn(t, slots[t] % 4));
            slots[t]++;
            p.sendMessage(PREFIX + "You are on the " + (t == 0 ? ChatColor.RED + "RED" : ChatColor.BLUE + "BLUE")
                    + ChatColor.GRAY + " team. Eliminate the other team!");
        }

        state = State.RUNNING;
        tell(ChatColor.GREEN + "Fight!" + ChatColor.GRAY + " Last team standing wins.");

        gameTask = new BukkitRunnable() {
            int left = GAME_SECONDS;

            @Override
            public void run() {
                if (state != State.RUNNING) {
                    cancel();
                    return;
                }
                left--;
                if (left == 60 || left == 30 || left == 10) {
                    tell(left + " seconds left!");
                }
                if (left <= 0) {
                    cancel();
                    endGame(-1);
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private int aliveOn(int teamId) {
        int n = 0;
        for (UUID id : alive) {
            Integer t = team.get(id);
            if (t != null && t == teamId) {
                n++;
            }
        }
        return n;
    }

    private void checkWin() {
        if (state != State.RUNNING) {
            return;
        }
        int red = aliveOn(0);
        int blue = aliveOn(1);
        if (red == 0 && blue == 0) {
            endGame(-1);
        } else if (red == 0) {
            endGame(1);
        } else if (blue == 0) {
            endGame(0);
        }
    }

    private void endGame(int winner) {
        if (state == State.ENDING) {
            return;
        }
        state = State.ENDING;
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        if (winner == 0) {
            tell(ChatColor.RED + "RED" + ChatColor.GRAY + " team wins!");
        } else if (winner == 1) {
            tell(ChatColor.BLUE + "BLUE" + ChatColor.GRAY + " team wins!");
        } else {
            tell("Draw - nobody won.");
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                inGame.clear();
                alive.clear();
                team.clear();
                for (UUID id : new ArrayList<UUID>(players)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) {
                        players.remove(id);
                        continue;
                    }
                    resetPlayer(p, GameMode.ADVENTURE);
                    p.teleport(lobbySpawn());
                }
                state = State.WAITING;
                tell("Back in the lobby. Next game starts when enough players are here.");
                tryCountdown();
            }
        }.runTaskLater(this, 100L);
    }

    private void eliminate(Player victim, Player killer) {
        UUID id = victim.getUniqueId();
        alive.remove(id);
        if (killer != null && !killer.equals(victim)) {
            tell(ChatColor.YELLOW + victim.getName() + ChatColor.GRAY + " was killed by "
                    + ChatColor.YELLOW + killer.getName());
        } else {
            tell(ChatColor.YELLOW + victim.getName() + ChatColor.GRAY + " died.");
        }
        victim.getInventory().clear();
        victim.getInventory().setArmorContents(new ItemStack[4]);
        victim.setHealth(victim.getMaxHealth());
        victim.setFireTicks(0);
        victim.setGameMode(GameMode.SPECTATOR);
        victim.teleport(new Location(world, 0.5, BASE_Y + 6, 0.5));
        victim.sendMessage(PREFIX + "You were eliminated. Watch the rest of the round.");
        checkWin();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Run this in game, not from the console.");
            return true;
        }
        Player p = (Player) sender;
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();

        if (sub.equals("join")) {
            join(p);
        } else if (sub.equals("leave")) {
            leave(p);
            p.sendMessage(PREFIX + "You left.");
        } else if (sub.equals("kit")) {
            if (args.length < 2 || !KITS.contains(args[1].toLowerCase())) {
                p.sendMessage(PREFIX + "Kits: warrior, archer, tank. Use /mb kit <name>");
            } else {
                kit.put(p.getUniqueId(), args[1].toLowerCase());
                p.sendMessage(PREFIX + "Kit set to " + ChatColor.AQUA + args[1].toLowerCase()
                        + ChatColor.GRAY + " (applies next round).");
            }
        } else if (sub.equals("start")) {
            if (!p.hasPermission("microbattles.admin")) {
                p.sendMessage(ChatColor.RED + "No permission.");
            } else if (state == State.RUNNING || state == State.ENDING) {
                p.sendMessage(PREFIX + "A game is already running.");
            } else if (players.isEmpty()) {
                p.sendMessage(PREFIX + "Join first with /mb join.");
            } else {
                startGame();
            }
        } else if (sub.equals("stop")) {
            if (!p.hasPermission("microbattles.admin")) {
                p.sendMessage(ChatColor.RED + "No permission.");
            } else if (state == State.RUNNING) {
                endGame(-1);
            } else {
                p.sendMessage(PREFIX + "No game is running.");
            }
        } else if (sub.equals("tp")) {
            if (!p.hasPermission("microbattles.admin")) {
                p.sendMessage(ChatColor.RED + "No permission.");
            } else {
                p.teleport(new Location(world, 0.5, BASE_Y + 1, 0.5));
                p.sendMessage(PREFIX + "Teleported to the arena. Use /mb leave to go back.");
            }
        } else {
            p.sendMessage(PREFIX + "/mb join | leave | kit <warrior|archer|tank>");
            if (p.hasPermission("microbattles.admin")) {
                p.sendMessage(PREFIX + "Admin: /mb start | stop | tp");
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ events

    private Player attackerOf(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent)) {
            return null;
        }
        Entity d = ((EntityDamageByEntityEvent) e).getDamager();
        if (d instanceof Player) {
            return (Player) d;
        }
        if (d instanceof Projectile) {
            ProjectileSource s = ((Projectile) d).getShooter();
            if (s instanceof Player) {
                return (Player) s;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) e.getEntity();
        UUID id = victim.getUniqueId();
        if (!players.contains(id)) {
            return;
        }
        if (state != State.RUNNING || !alive.contains(id)) {
            e.setCancelled(true);
            return;
        }
        Player attacker = attackerOf(e);
        if (attacker != null) {
            Integer a = team.get(attacker.getUniqueId());
            Integer v = team.get(id);
            if (a != null && a.equals(v) && !attacker.equals(victim)) {
                e.setCancelled(true);
                return;
            }
        }
        if (victim.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            eliminate(victim, attacker);
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && players.contains(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (players.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (players.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (players.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        if (players.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (players.contains(p.getUniqueId())) {
            leave(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld().equals(world) && !players.contains(p.getUniqueId())) {
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
    }
}
