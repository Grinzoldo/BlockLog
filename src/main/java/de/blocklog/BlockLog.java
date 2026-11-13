package de.blocklog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockLog extends JavaPlugin implements Listener, TabExecutor {

    private static final String SEP = "\u001F";
    private static final TextColor TIME_COLOR = TextColor.color(0x31C5CF);

    /** Zustand eines geoeffneten Containers, um beim Schliessen den Unterschied zu berechnen. */
    private record Session(Location location, Map<String, Integer> before, String container) {
    }

    /** Letztes Abfrage-Ergebnis eines Spielers, damit man blaettern kann. */
    private record Result(String title, List<Entry> entries, boolean showCoords) {
    }

    private Database database;
    private DateTimeFormatter timeFormat;
    private int pageSize;
    private int maxRadius;
    private int maxResults;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Result> results = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pageSize = Math.max(3, getConfig().getInt("page-size", 10));
        maxRadius = Math.max(1, getConfig().getInt("max-radius", 100));
        maxResults = Math.max(100, getConfig().getInt("max-results", 2000));

        ZoneId zone;
        try {
            zone = ZoneId.of(getConfig().getString("timezone", "Europe/Berlin"));
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        timeFormat = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(zone);

        getDataFolder().mkdirs();
        try {
            database = new Database(new File(getDataFolder(), "blocklog.db"), getLogger());
        } catch (Exception e) {
            getLogger().severe("Die Datenbank konnte nicht geoeffnet werden: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("check");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        // Alle 5 Sekunden wartende Eintraege in die Datei schreiben (nicht auf dem Haupt-Thread).
        getServer().getScheduler().runTaskTimerAsynchronously(this, database::flush, 100L, 100L);
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }

    // ------------------------------------------------------------------
    // Protokollieren
    // ------------------------------------------------------------------

    private void log(Player player, LogAction action, Location location, String material, int amount, String extra) {
        if (location.getWorld() == null) {
            return;
        }
        database.enqueue(new Entry(System.currentTimeMillis(), player.getName(), action,
                location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                material, amount, extra));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        log(event.getPlayer(), LogAction.PLACE, block.getLocation(), block.getType().name(), 1, "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        log(event.getPlayer(), LogAction.BREAK, block.getLocation(), block.getType().name(), 1, "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        StringBuilder text = new StringBuilder(event.getSide().name());
        for (Component line : event.lines()) {
            text.append(SEP).append(PlainTextComponentSerializer.plainText().serialize(line));
        }
        Block block = event.getBlock();
        log(event.getPlayer(), LogAction.SIGN, block.getLocation(), block.getType().name(), 1, text.toString());
    }

    /** Merkt sich beim Oeffnen eines Containers dessen Inhalt. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        if (!isBlockContainer(top)) {
            return;
        }
        Location location = top.getLocation();
        String container = location.getBlock().getType().name();
        sessions.put(player.getUniqueId(), new Session(location, counts(top), container));
    }

    /** Beim Schliessen: Unterschied zum Zustand beim Oeffnen = hineingelegt / herausgenommen. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        Map<String, Integer> after = counts(event.getInventory());
        Set<String> all = new HashSet<>(session.before().keySet());
        all.addAll(after.keySet());
        for (String material : all) {
            int delta = after.getOrDefault(material, 0) - session.before().getOrDefault(material, 0);
            if (delta > 0) {
                log(player, LogAction.PUT, session.location(), material, delta, session.container());
            } else if (delta < 0) {
                log(player, LogAction.TAKE, session.location(), material, -delta, session.container());
            }
        }
    }

    private boolean isBlockContainer(Inventory inventory) {
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof BlockState || holder instanceof DoubleChest;
    }

    private Map<String, Integer> counts(Inventory inventory) {
        Map<String, Integer> map = new HashMap<>();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() != Material.AIR) {
                map.merge(stack.getType().name(), stack.getAmount(), Integer::sum);
            }
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Holzspitzhacke = Inspektor
    // ------------------------------------------------------------------

    private boolean isInspector(Player player) {
        if (!player.hasPermission("blocklog.use")) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        return hand.getType() == Material.WOODEN_PICKAXE;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInspectClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!isInspector(player)) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        event.setCancelled(true);
        inspectBlock(player, block);
    }

    /** Mit der Holzspitzhacke wird nichts abgebaut. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInspectBreak(BlockBreakEvent event) {
        if (isInspector(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private void inspectBlock(Player player, Block block) {
        String world = block.getWorld().getName();
        int bx = block.getX();
        int by = block.getY();
        int bz = block.getZ();

        // Bei Containern (auch Doppelkisten) gilt die Position des Inventars.
        int cx = bx;
        int cy = by;
        int cz = bz;
        if (block.getState() instanceof Container container) {
            Location location = container.getInventory().getLocation();
            if (location != null) {
                cx = location.getBlockX();
                cy = location.getBlockY();
                cz = location.getBlockZ();
            }
        }
        final int fcx = cx;
        final int fcy = cy;
        final int fcz = cz;

        String title = block.getType().name() + " bei " + bx + " " + by + " " + bz;
        UUID id = player.getUniqueId();

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                List<Entry> entries = database.blockHistory(world, bx, by, bz, fcx, fcy, fcz, maxResults);
                getServer().getScheduler().runTask(this, () -> {
                    Result result = new Result(title, entries, false);
                    results.put(id, result);
                    show(player, result, 1);
                });
            } catch (SQLException e) {
                reportError(player, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Befehl /check
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl geht nur im Spiel.");
            return true;
        }
        if (!player.hasPermission("blocklog.use")) {
            player.sendMessage(Component.text("Dafür hast du keine Berechtigung.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("page")) {
            handlePage(player, args);
            return true;
        }

        String number = first.equals("radius") ? (args.length > 1 ? args[1] : "") : args[0];
        int radius;
        try {
            radius = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            sendUsage(player);
            return true;
        }
        if (radius < 1 || radius > maxRadius) {
            player.sendMessage(Component.text("Der Radius muss zwischen 1 und " + maxRadius + " liegen.",
                    NamedTextColor.RED));
            return true;
        }
        runAreaCheck(player, radius);
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(Component.text("Benutzung: /check <radius>  |  /check page <seite>", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Oder mit der Holzspitzhacke auf einen Block klicken.", NamedTextColor.YELLOW));
    }

    private void handlePage(Player player, String[] args) {
        Result result = results.get(player.getUniqueId());
        if (result == null) {
            player.sendMessage(Component.text(
                    "Es gibt noch kein Ergebnis. Nutze /check <radius> oder die Holzspitzhacke.", NamedTextColor.RED));
            return;
        }
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sendUsage(player);
                return;
            }
        }
        show(player, result, page);
    }

    private void runAreaCheck(Player player, int radius) {
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        String world = location.getWorld().getName();
        int px = location.getBlockX();
        int py = location.getBlockY();
        int pz = location.getBlockZ();
        UUID id = player.getUniqueId();
        String title = "Umkreis " + radius + " Blöcke";

        player.sendMessage(Component.text("Suche...", NamedTextColor.GRAY));

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                List<Entry> entries = database.areaHistory(world,
                        px - radius, px + radius, py - radius, py + radius, pz - radius, pz + radius, maxResults);
                getServer().getScheduler().runTask(this, () -> {
                    Result result = new Result(title, entries, true);
                    results.put(id, result);
                    show(player, result, 1);
                });
            } catch (SQLException e) {
                reportError(player, e);
            }
        });
    }

    private void reportError(Player player, SQLException e) {
        getLogger().severe("Abfrage fehlgeschlagen: " + e.getMessage());
        getServer().getScheduler().runTask(this, () ->
                player.sendMessage(Component.text("Die Abfrage ist fehlgeschlagen. Siehe Konsole.", NamedTextColor.RED)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("blocklog.use")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            for (String option : List.of("radius", "page", "10", "20", "50")) {
                if (option.startsWith(prefix)) {
                    options.add(option);
                }
            }
            return options;
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Anzeige
    // ------------------------------------------------------------------

    private void show(Player player, Result result, int requestedPage) {
        List<Entry> entries = result.entries();
        int pages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int page = Math.max(1, Math.min(requestedPage, pages));

        player.sendMessage(Component.text("---- " + result.title() + " (Seite " + page + "/" + pages
                + ", " + entries.size() + " Einträge) ----", TIME_COLOR));

        if (entries.isEmpty()) {
            player.sendMessage(Component.text("Keine Einträge gefunden. Das Protokoll beginnt erst, "
                    + "seit das Plugin installiert ist.", NamedTextColor.GRAY));
            return;
        }

        int from = (page - 1) * pageSize;
        int to = Math.min(entries.size(), from + pageSize);
        for (int i = from; i < to; i++) {
            for (Component line : render(entries.get(i), result.showCoords())) {
                player.sendMessage(line);
            }
        }

        if (pages > 1) {
            Component nav = Component.empty();
            if (page > 1) {
                nav = nav.append(button("<< Zurück", "/check page " + (page - 1)));
            }
            nav = nav.append(Component.text("   "));
            if (page < pages) {
                nav = nav.append(button("Weiter >>", "/check page " + (page + 1)));
            }
            player.sendMessage(nav);
        }
    }

    private Component button(String label, String command) {
        return Component.text("[" + label + "]", NamedTextColor.GOLD)
                .clickEvent(ClickEvent.runCommand(command));
    }

    private List<Component> render(Entry e, boolean coords) {
        Component head = Component.text("[" + timeFormat.format(Instant.ofEpochMilli(e.time())) + "] ", TIME_COLOR)
                .append(Component.text(e.player(), NamedTextColor.YELLOW));
        Component where = coords
                ? Component.text(" (" + e.x() + " " + e.y() + " " + e.z() + ")", NamedTextColor.GRAY)
                : Component.empty();
        String container = e.extra().isEmpty() ? "CONTAINER" : e.extra();

        List<Component> lines = new ArrayList<>();
        switch (e.action()) {
            case PLACE -> lines.add(head
                    .append(Component.text(" platzierte ", NamedTextColor.GREEN))
                    .append(Component.text(e.material(), NamedTextColor.BLUE))
                    .append(where));
            case BREAK -> lines.add(head
                    .append(Component.text(" baute ", NamedTextColor.RED))
                    .append(Component.text(e.material(), NamedTextColor.BLUE))
                    .append(Component.text(" ab", NamedTextColor.RED))
                    .append(where));
            case PUT -> lines.add(head
                    .append(Component.text(" legte ", NamedTextColor.GREEN))
                    .append(Component.text(e.amount() + "x ", NamedTextColor.WHITE))
                    .append(Component.text(e.material(), NamedTextColor.BLUE))
                    .append(Component.text(" in ", NamedTextColor.GREEN))
                    .append(Component.text(container, NamedTextColor.BLUE))
                    .append(where));
            case TAKE -> lines.add(head
                    .append(Component.text(" nahm ", NamedTextColor.RED))
                    .append(Component.text(e.amount() + "x ", NamedTextColor.WHITE))
                    .append(Component.text(e.material(), NamedTextColor.BLUE))
                    .append(Component.text(" aus ", NamedTextColor.RED))
                    .append(Component.text(container, NamedTextColor.BLUE))
                    .append(where));
            case SIGN -> {
                String[] parts = e.extra().split(SEP, -1);
                String side = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "FRONT";
                lines.add(head
                        .append(Component.text(" beschriftete ", NamedTextColor.GREEN))
                        .append(Component.text(e.material(), NamedTextColor.BLUE))
                        .append(Component.text(" auf ", NamedTextColor.GREEN))
                        .append(Component.text(side, NamedTextColor.GREEN))
                        .append(Component.text(":", NamedTextColor.GREEN))
                        .append(where));
                Component body = Component.empty();
                boolean any = false;
                for (int i = 1; i < parts.length; i++) {
                    if (!parts[i].isBlank()) {
                        body = body.append(Component.text("[" + parts[i] + "] ", NamedTextColor.BLUE));
                        any = true;
                    }
                }
                lines.add(any ? body : Component.text("(leer)", NamedTextColor.GRAY));
            }
        }
        return lines;
    }
}
