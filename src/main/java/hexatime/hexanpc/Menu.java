package hexatime.hexanpc;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Меню-сундук: слот -> действие при клике. */
public class Menu implements InventoryHolder {
    public Inventory inventory;
    public final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
