package hexatime.hexanpc;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class HexaNpc extends JavaPlugin implements Listener {

    private NamespacedKey npcKey;
    private Economy economy;
    private final Map<UUID, BukkitTask> teleports = new HashMap<>();
    private final Map<String, Long> quizCooldowns = new HashMap<>();
    private final Random random = new Random();

    // ================= Запуск =================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        npcKey = new NamespacedKey(this, "npc_id");
        setupEconomy();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void setupEconomy() {
        economy = null;
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault не найден: скупщик и продавцы работать не будут.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("Экономика в Vault не найдена: скупщик и продавцы работать не будут.");
            return;
        }
        economy = rsp.getProvider();
    }

    // ================= Утилиты =================

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, key));
    }

    private String money(double v) {
        return economy != null ? economy.format(v) : String.format("%.2f", v);
    }

    private ConfigurationSection npcSection(String id) {
        return getConfig().getConfigurationSection("npcs." + id);
    }

    private Menu newMenu(int size, String title) {
        Menu m = new Menu();
        m.inventory = Bukkit.createInventory(m, size, color(title));
        return m;
    }

    private ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(color(s));
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    // ================= Команды =================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/hnpc spawn <id> | remove | list | reload");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                setupEconomy();
                sender.sendMessage(ChatColor.GREEN + "Перезагружено.");
            }
            case "list" -> {
                ConfigurationSection npcs = getConfig().getConfigurationSection("npcs");
                if (npcs == null) {
                    sender.sendMessage(ChatColor.RED + "В конфиге нет NPC.");
                    return true;
                }
                for (String id : npcs.getKeys(false)) {
                    sender.sendMessage(ChatColor.YELLOW + id + ChatColor.GRAY + " - "
                            + npcs.getString(id + ".type", "?"));
                }
            }
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Только для игрока.");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "/hnpc spawn <id>  (список: /hnpc list)");
                    return true;
                }
                ConfigurationSection s = npcSection(args[1]);
                if (s == null) {
                    p.sendMessage(ChatColor.RED + "NPC с id '" + args[1] + "' нет в конфиге.");
                    return true;
                }
                spawnNpc(p.getLocation(), args[1], s);
                p.sendMessage(ChatColor.GREEN + "NPC '" + args[1] + "' создан.");
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Только для игрока.");
                    return true;
                }
                Entity nearest = null;
                double best = Double.MAX_VALUE;
                for (Entity e : p.getNearbyEntities(5, 5, 5)) {
                    if (npcId(e) != null) {
                        double d = e.getLocation().distanceSquared(p.getLocation());
                        if (d < best) {
                            best = d;
                            nearest = e;
                        }
                    }
                }
                if (nearest == null) {
                    p.sendMessage(ChatColor.RED + "Рядом (5 блоков) нет NPC.");
                } else {
                    nearest.remove();
                    p.sendMessage(ChatColor.GREEN + "NPC удалён.");
                }
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/hnpc spawn <id> | remove | list | reload");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("spawn", "remove", "list", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            ConfigurationSection npcs = getConfig().getConfigurationSection("npcs");
            if (npcs != null) {
                for (String id : npcs.getKeys(false)) {
                    if (id.toLowerCase().startsWith(args[1].toLowerCase())) out.add(id);
                }
            }
        }
        return out;
    }

    // ================= NPC =================

    private void spawnNpc(Location loc, String id, ConfigurationSection s) {
        String name = color(s.getString("name", id));
        String profName = s.getString("profession", "nitwit").toLowerCase();
        Villager.Profession prof = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(profName));
        if (prof == null) prof = Villager.Profession.NITWIT;
        final Villager.Profession finalProf = prof;
        loc.getWorld().spawn(loc, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.setProfession(finalProf);
            v.setCustomName(name);
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, id);
        });
    }

    private String npcId(Entity e) {
        return e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        String id = npcId(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true); // без стандартной торговли жителя
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("hexanpc.use")) return;

        ConfigurationSection s = npcSection(id);
        if (s == null) {
            p.sendMessage(ChatColor.RED + "Настройки этого NPC ('" + id + "') не найдены в конфиге.");
            return;
        }
        switch (s.getString("type", "").toUpperCase()) {
            case "BUYER" -> openBuyer(p, s);
            case "SELLER" -> openSeller(p, s);
            case "TELEPORT" -> startTeleport(p, s);
            case "QUIZ" -> openQuiz(p, id, s);
            default -> p.sendMessage(ChatColor.RED + "Неизвестный тип NPC в конфиге.");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (npcId(e.getEntity()) != null) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof Player p && teleports.containsKey(p.getUniqueId())) {
            cancelTeleport(p, "tp-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelTeleport(e.getPlayer(), null);
    }

    // ================= Клики в меню =================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        var action = menu.actions.get(e.getSlot());
        if (action != null) action.accept(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ================= СКУПЩИК =================

    private int countPlain(Player p, Material mat) {
        ItemStack plain = new ItemStack(mat);
        int c = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.isSimilar(plain)) c += it.getAmount();
        }
        return c;
    }

    private int takePlain(Player p, Material mat) {
        ItemStack plain = new ItemStack(mat);
        ItemStack[] contents = p.getInventory().getStorageContents();
        int removed = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && it.isSimilar(plain)) {
                removed += it.getAmount();
                contents[i] = null;
            }
        }
        p.getInventory().setStorageContents(contents);
        return removed;
    }

    private Map<Material, Double> buyerPrices(ConfigurationSection s) {
        Map<Material, Double> prices = new LinkedHashMap<>();
        ConfigurationSection items = s.getConfigurationSection("items");
        if (items == null) return prices;
        for (String key : items.getKeys(false)) {
            Material mat = Material.matchMaterial(key);
            if (mat == null) {
                getLogger().warning("Скупщик: неизвестный материал " + key);
                continue;
            }
            prices.put(mat, items.getDouble(key));
            if (prices.size() >= 45) break;
        }
        return prices;
    }

    private void openBuyer(Player p, ConfigurationSection s) {
        if (economy == null) {
            p.sendMessage(msg("no-economy"));
            return;
        }
        Map<Material, Double> prices = buyerPrices(s);
        Menu menu = newMenu(54, s.getString("title", "Скупщик"));
        int slot = 0;
        for (Map.Entry<Material, Double> en : prices.entrySet()) {
            Material mat = en.getKey();
            double price = en.getValue();
            menu.inventory.setItem(slot, icon(mat, "&f" + prettyName(mat), List.of(
                    "&7Цена: &a" + money(price) + " &7за 1 шт.",
                    "&7У тебя: &f" + countPlain(p, mat),
                    "",
                    "&eНажми - продать всё")));
            menu.actions.put(slot, pl -> {
                int n = takePlain(pl, mat);
                if (n == 0) {
                    pl.sendMessage(msg("nothing-to-sell"));
                } else {
                    double total = n * price;
                    economy.depositPlayer(pl, total);
                    pl.sendMessage(msg("sold").replace("%amount%", String.valueOf(n)).replace("%money%", money(total)));
                }
                Bukkit.getScheduler().runTask(this, () -> openBuyer(pl, s));
            });
            slot++;
        }
        menu.inventory.setItem(49, icon(Material.EMERALD, "&a&lПродать всё из прайса",
                List.of("&7Продаст все подходящие предметы", "&7из твоего инвентаря")));
        menu.actions.put(49, pl -> {
            int amount = 0;
            double total = 0;
            for (Map.Entry<Material, Double> en : prices.entrySet()) {
                int n = takePlain(pl, en.getKey());
                amount += n;
                total += n * en.getValue();
            }
            if (amount == 0) {
                pl.sendMessage(msg("nothing-to-sell"));
            } else {
                economy.depositPlayer(pl, total);
                pl.sendMessage(msg("sold").replace("%amount%", String.valueOf(amount)).replace("%money%", money(total)));
            }
            Bukkit.getScheduler().runTask(this, () -> openBuyer(pl, s));
        });
        p.openInventory(menu.inventory);
    }

    private String prettyName(Material mat) {
        String[] parts = mat.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ================= ПРОДАВЕЦ =================

    private void openSeller(Player p, ConfigurationSection s) {
        if (economy == null) {
            p.sendMessage(msg("no-economy"));
            return;
        }
        Menu menu = newMenu(54, s.getString("title", "Продавец"));
        int slot = 0;
        for (Map<?, ?> map : s.getMapList("items")) {
            if (slot >= 54) break;
            try {
                Material mat = Material.matchMaterial(String.valueOf(map.get("material")));
                if (mat == null) {
                    getLogger().warning("Продавец: неизвестный материал " + map.get("material"));
                    continue;
                }
                int amount = map.containsKey("amount") ? Integer.parseInt(String.valueOf(map.get("amount"))) : 1;
                double price = Double.parseDouble(String.valueOf(map.get("price")));
                ItemStack shown = icon(mat, "&f" + prettyName(mat), List.of(
                        "&7Количество: &f" + amount,
                        "&7Цена: &a" + money(price),
                        "",
                        "&eНажми - купить"));
                shown.setAmount(Math.max(1, Math.min(amount, mat.getMaxStackSize())));
                menu.inventory.setItem(slot, shown);
                menu.actions.put(slot, pl -> buy(pl, mat, amount, price));
                slot++;
            } catch (Exception ex) {
                getLogger().warning("Продавец: ошибка в предмете: " + ex.getMessage());
            }
        }
        p.openInventory(menu.inventory);
    }

    private void buy(Player p, Material mat, int amount, double price) {
        if (!economy.has(p, price)) {
            p.sendMessage(msg("not-enough-money").replace("%price%", money(price)));
            return;
        }
        if (!economy.withdrawPlayer(p, price).transactionSuccess()) {
            p.sendMessage(msg("not-enough-money").replace("%price%", money(price)));
            return;
        }
        int left = amount;
        int max = mat.getMaxStackSize();
        while (left > 0) {
            int n = Math.min(left, max);
            Map<Integer, ItemStack> rest = p.getInventory().addItem(new ItemStack(mat, n));
            for (ItemStack r : rest.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), r);
            }
            left -= n;
        }
        p.sendMessage(msg("bought").replace("%item%", amount + "x " + prettyName(mat)).replace("%price%", money(price)));
    }

    // ================= ТЕЛЕПОРТ =================

    private void startTeleport(Player p, ConfigurationSection s) {
        UUID uuid = p.getUniqueId();
        if (teleports.containsKey(uuid)) {
            p.sendMessage(msg("tp-already"));
            return;
        }
        World world = Bukkit.getWorld(s.getString("world", "world"));
        if (world == null) {
            p.sendMessage(msg("tp-error"));
            return;
        }
        Location dest = new Location(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
        int delay = Math.max(0, s.getInt("delay", 10));
        boolean cancelOnMove = s.getBoolean("cancel-on-move", true);
        Location start = p.getLocation().clone();

        p.sendMessage(msg("tp-start").replace("%seconds%", String.valueOf(delay)));

        BukkitTask task = new BukkitRunnable() {
            int left = delay;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    teleports.remove(uuid);
                    return;
                }
                if (cancelOnMove && moved(start, p.getLocation())) {
                    cancel();
                    teleports.remove(uuid);
                    p.sendMessage(msg("tp-moved"));
                    return;
                }
                if (left <= 0) {
                    cancel();
                    teleports.remove(uuid);
                    p.teleport(dest);
                    p.sendMessage(msg("tp-done"));
                    return;
                }
                p.sendActionBar(LegacyComponentSerializer.legacySection()
                        .deserialize(msg("tp-tick").replace("%seconds%", String.valueOf(left))));
                left--;
            }
        }.runTaskTimer(this, 0L, 20L);
        teleports.put(uuid, task);
    }

    private boolean moved(Location a, Location b) {
        return a.getWorld() != b.getWorld()
                || a.getBlockX() != b.getBlockX()
                || a.getBlockY() != b.getBlockY()
                || a.getBlockZ() != b.getBlockZ();
    }

    private void cancelTeleport(Player p, String messageKey) {
        BukkitTask t = teleports.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
            if (messageKey != null) p.sendMessage(msg(messageKey));
        }
    }

    // ================= ВОПРОСЫ =================

    private static class Question {
        String text;
        List<String> answers = new ArrayList<>();
        int correct;
        List<String> commands = new ArrayList<>();
    }

    private List<Question> parseQuestions(ConfigurationSection s) {
        List<Question> list = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> map : s.getMapList("questions")) {
            index++;
            try {
                Question q = new Question();
                q.text = String.valueOf(map.get("question"));
                if (map.get("answers") instanceof List<?> l) {
                    for (Object o : l) q.answers.add(String.valueOf(o));
                }
                q.correct = Integer.parseInt(String.valueOf(map.get("correct")));
                if (map.get("commands") instanceof List<?> l) {
                    for (Object o : l) q.commands.add(String.valueOf(o));
                }
                if (q.answers.size() < 2 || q.answers.size() > 4 || q.correct < 0 || q.correct >= q.answers.size()) {
                    getLogger().warning("Вопрос #" + index + " пропущен: нужно 2-4 ответа и верный correct (с 0).");
                    continue;
                }
                list.add(q);
            } catch (Exception ex) {
                getLogger().warning("Вопрос #" + index + " пропущен: " + ex.getMessage());
            }
        }
        return list;
    }

    private static final int[] ANSWER_SLOTS = {10, 12, 14, 16};
    private static final Material[] ANSWER_ICONS = {
            Material.BLUE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL, Material.ORANGE_WOOL};
    private static final String[] LETTERS = {"A", "B", "C", "D"};

    private void openQuiz(Player p, String id, ConfigurationSection s) {
        List<Question> questions = parseQuestions(s);
        if (questions.isEmpty()) {
            p.sendMessage(msg("quiz-empty"));
            return;
        }
        String cdKey = id + ":" + p.getUniqueId();
        long left = quizCooldowns.getOrDefault(cdKey, 0L) - System.currentTimeMillis();
        if (left > 0) {
            p.sendMessage(msg("quiz-cooldown").replace("%seconds%", String.valueOf((left + 999) / 1000)));
            return;
        }
        Question q = questions.get(random.nextInt(questions.size()));
        Menu menu = newMenu(27, s.getString("title", "Вопрос"));
        menu.inventory.setItem(4, icon(Material.PAPER, "&6Вопрос", wrap(q.text)));

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < q.answers.size(); i++) order.add(i);
        Collections.shuffle(order, random);

        final boolean[] answered = {false};
        long cooldownMs = s.getLong("cooldown-seconds", 60) * 1000L;
        for (int i = 0; i < order.size(); i++) {
            int answerIndex = order.get(i);
            int slot = ANSWER_SLOTS[i];
            menu.inventory.setItem(slot, icon(ANSWER_ICONS[i],
                    "&e" + LETTERS[i] + ") &f" + q.answers.get(answerIndex), List.of()));
            menu.actions.put(slot, pl -> {
                if (answered[0]) return;
                answered[0] = true;
                quizCooldowns.put(cdKey, System.currentTimeMillis() + cooldownMs);
                Bukkit.getScheduler().runTask(this, () -> {
                    pl.closeInventory();
                    if (answerIndex == q.correct) {
                        pl.sendMessage(msg("quiz-correct"));
                        for (String cmd : q.commands) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", pl.getName()));
                        }
                    } else {
                        pl.sendMessage(msg("quiz-wrong"));
                    }
                });
            });
        }
        p.openInventory(menu.inventory);
    }

    private List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() > 32) {
                lines.add("&f" + line.toString().trim());
                line = new StringBuilder();
            }
            line.append(word).append(' ');
        }
        if (line.length() > 0) lines.add("&f" + line.toString().trim());
        return lines;
    }
}
