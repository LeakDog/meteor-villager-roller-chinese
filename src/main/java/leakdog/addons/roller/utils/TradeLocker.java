package leakdog.addons.roller.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 与村民完成一次交易，从而永久锁定其职业与交易列表。
 *
 * <p>村民在首次被交易后职业就固定下来，之后破坏工作方块也不会再重置。所以刷到目标附魔后
 * 立刻买一本，就能把这个交易保住，不必担心误操作或服务器重启导致白刷。
 *
 * <p>这个类只负责“执行一次交易”，是否要做、以及做完之后怎么处理由调用方决定。
 * 所有操作都要求交易界面当前是打开状态。
 */
public class TradeLocker {
    /** 交易界面里结果槽的槽位号。0 与 1 是两个付款槽，2 是产出槽。 */
    private static final int RESULT_SLOT = 2;

    /** 一次锁定尝试的结果。 */
    public enum Status {
        /** 已发出交易与取物操作。 */
        SUCCESS,
        /** 交易界面不在打开状态，通常是被其他界面顶掉了。 */
        NO_SCREEN,
        /** 传入的交易序号在当前交易列表里不存在。 */
        BAD_INDEX,
        /** 该交易已售罄，需要等村民补货。 */
        OUT_OF_STOCK,
        /** 背包里的物品不足以支付。 */
        NOT_ENOUGH_ITEMS,
        /** 背包没有空位放产出物。 */
        INVENTORY_FULL
    }

    /** 锁定结果，{@code detail} 是可直接展示给玩家的中文说明。 */
    public record Result(Status status, String detail) {
        public boolean ok() {
            return status == Status.SUCCESS;
        }
    }

    private TradeLocker() {}

    /**
     * 尝试用第 {@code index} 个交易完成一次购买。
     *
     * <p>调用前必须确保交易界面仍然打开。执行顺序与原版客户端一致：
     * 先本地选中交易并同步给服务端，再从结果槽 shift 取物。
     */
    public static Result lock(int index) {
        if (mc.player == null) return new Result(Status.NO_SCREEN, "玩家不存在");
        if (!(mc.player.containerMenu instanceof MerchantMenu menu)) {
            return new Result(Status.NO_SCREEN, "交易界面未打开");
        }

        MerchantOffers offers = menu.getOffers();
        if (index < 0 || index >= offers.size()) {
            return new Result(Status.BAD_INDEX, "交易序号 " + index + " 不存在");
        }

        MerchantOffer offer = offers.get(index);
        if (offer.isOutOfStock()) {
            return new Result(Status.OUT_OF_STOCK, "该交易已售罄，需等村民补货");
        }

        // 先确认付得起，避免白白点一次让服务端拒绝
        Result affordable = checkAffordable(offer);
        if (!affordable.ok()) return affordable;

        if (!hasRoomForResult(offer.getResult())) {
            return new Result(Status.INVENTORY_FULL, "背包已满，放不下产出物");
        }

        // 本地选中 + 通知服务端，两者都要做：前者让客户端把付款物摆进付款槽，
        // 后者让服务端知道我们选的是哪一条交易
        menu.setSelectionHint(index);
        menu.tryMoveItems(index);
        mc.getConnection().send(new ServerboundSelectTradePacket(index));

        // shift 点击结果槽，等价于玩家按住 Shift 取走产出物
        InvUtils.shiftClick().slotId(RESULT_SLOT);

        return new Result(Status.SUCCESS, describeCost(offer));
    }

    /** 背包里的物品是否够支付这笔交易的两项成本。 */
    private static Result checkAffordable(MerchantOffer offer) {
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();

        if (!costA.isEmpty()) {
            int have = countInInventory(costA);
            if (have < costA.getCount()) {
                return new Result(Status.NOT_ENOUGH_ITEMS, String.format("%s 不足：需要 %d，只有 %d",
                    describeItem(costA), costA.getCount(), have));
            }
        }

        if (!costB.isEmpty()) {
            int have = countInInventory(costB);
            if (have < costB.getCount()) {
                return new Result(Status.NOT_ENOUGH_ITEMS, String.format("%s 不足：需要 %d，只有 %d",
                    describeItem(costB), costB.getCount(), have));
            }
        }

        return new Result(Status.SUCCESS, "");
    }

    /**
     * 统计背包里能用于支付的数量。
     *
     * <p>用交易自身的 {@code satisfiedBy} 无法单独校验某一项成本，所以这里按物品与
     * 组件是否匹配来数，判定方式与原版付款槽的接受条件一致。
     */
    private static int countInInventory(ItemStack cost) {
        if (mc.player == null) return 0;

        int total = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(stack, cost)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 背包里是否有地方放产出物。 */
    private static boolean hasRoomForResult(ItemStack result) {
        if (mc.player == null) return false;
        if (result.isEmpty()) return true;

        var inv = mc.player.getInventory();
        if (inv.getFreeSlot() != -1) return true;

        // 没有空格时，能并入已有堆叠也算有位置
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(stack, result)
                && stack.getCount() + result.getCount() <= inv.getMaxStackSize(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 把交易成本描述成「32 个绿宝石 + 1 本书」这样的文本。 */
    private static String describeCost(MerchantOffer offer) {
        StringBuilder sb = new StringBuilder();
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();

        if (!costA.isEmpty()) {
            sb.append(costA.getCount()).append(" 个").append(describeItem(costA));
        }
        if (!costB.isEmpty()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(costB.getCount()).append(" 个").append(describeItem(costB));
        }
        return sb.toString();
    }

    private static String describeItem(ItemStack stack) {
        return stack.getHoverName().getString();
    }
}
