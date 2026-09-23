package leakdog.addons.roller.utils;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 把背包里的工作方块补到快捷栏。
 *
 * <p>刷取时讲台只从快捷栏取用，用完就会停下。这个类负责在快捷栏空了之后，
 * 从背包主格子里把同类方块挪过来，让刷取能继续。
 */
public class InventoryRefiller {
    private InventoryRefiller() {}

    /** 补货结果。 */
    public enum Status {
        /** 已把方块挪到快捷栏。 */
        MOVED,
        /** 快捷栏里本来就有，不需要补。 */
        ALREADY_PRESENT,
        /** 背包里也没有了。 */
        NOT_FOUND,
        /** 快捷栏没有可用格子放它。 */
        NO_HOTBAR_SLOT
    }

    /**
     * 确保快捷栏里有指定方块可用，必要时从背包补一组过来。
     *
     * <p>只做物品栏内的搬运，不涉及任何方块破坏或放置。
     */
    public static Status ensureInHotbar(Item item) {
        if (mc.player == null || item == null) return Status.NOT_FOUND;

        // 快捷栏（含副手）里已经有了就不用动
        if (InvUtils.findInHotbar(item).found()) return Status.ALREADY_PRESENT;

        // 在背包主格子里找
        FindItemResult inMain = InvUtils.find(stack -> stack.getItem() == item);
        if (!inMain.found() || !inMain.isMain()) return Status.NOT_FOUND;

        int targetHotbarSlot = findUsableHotbarSlot();
        if (targetHotbarSlot == -1) return Status.NO_HOTBAR_SLOT;

        // 空格优先直接移动，否则与目标格子交换
        InvUtils.move().from(inMain.slot()).toHotbar(targetHotbarSlot);
        return Status.MOVED;
    }

    /**
     * 挑一个可以放方块的快捷栏格子。
     *
     * <p>优先用空格；全满时退回当前手持格子之外的一格，把原物品换进背包，
     * 避免把玩家正拿着的东西弄丢。
     */
    private static int findUsableHotbarSlot() {
        if (mc.player == null) return -1;

        var inv = mc.player.getInventory();

        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }

        // 全满时避开当前手持格
        int selected = inv.getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (i != selected) return i;
        }
        return -1;
    }

    /** 统计整个背包（含快捷栏）里该方块的总数，用于判断是否需要去捡。 */
    public static int countInInventory(Item item) {
        if (mc.player == null || item == null) return 0;

        int total = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }
}
