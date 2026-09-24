package leakdog.addons.roller.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 调用 Baritone 走到附近掉落的工作方块旁边，靠玩家碰撞自然拾取。
 *
 * <p>全程通过反射访问 Baritone，因为它是运行时可选依赖，直接编译引用会让没装 Baritone
 * 的玩家加载失败。
 *
 * <p><b>绝不破坏或放置方块。</b>Baritone 默认允许挖穿障碍和搭桥来抵达目标，所以每次寻路前
 * 都会强制把 {@code allowBreak} 与 {@code allowPlace} 关掉。用的是「走到目标附近」而不是
 * Baritone 自带的 mine 功能，后者会挖方块。
 */
public class ItemCollector {
    /** Baritone 是否可用。 */
    private static Boolean available;

    /** 上一次是否改动过 Baritone 设置，用于在停止后恢复。 */
    private static Boolean previousAllowBreak;
    private static Boolean previousAllowPlace;

    /** 判定为到不了的掉落物位置，不再重复尝试。 */
    private static final Set<BlockPos> unreachable = new HashSet<>();

    /** 当前正在前往的目标，用于判断寻路是否失败。 */
    private static BlockPos currentTarget;
    /** 开始前往当前目标时的时间，用于超时判定。 */
    private static long targetStartedAt;

    /** 单个目标允许尝试的最长时间。超过就认为到不了。 */
    private static final long TARGET_TIMEOUT_MS = 15_000;

    private ItemCollector() {}

    /** 拾取请求的结果。 */
    public enum Status {
        /** 已让 Baritone 出发。 */
        PATHING,
        /** 正在前往目标，无需重复下令。 */
        ALREADY_PATHING,
        /** 附近有掉落物，但都到不了。 */
        ALL_UNREACHABLE,
        /** 附近没有找到掉落的方块。 */
        NO_ITEM_NEARBY,
        /** 没装 Baritone 或反射调用失败。 */
        UNAVAILABLE
    }

    public static boolean isAvailable() {
        if (available == null) {
            try {
                // 只检查类在不在，不触发静态初始化：Baritone 的初始化依赖游戏环境，
                // 带初始化的 forName 会在时机不对时抛 ExceptionInInitializerError，
                // 那样会被误判成「没装 Baritone」
                Class.forName("baritone.api.BaritoneAPI", false,
                    ItemCollector.class.getClassLoader());
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /**
     * 在指定半径内找最近的、尚未被判定为不可达的掉落方块，并让 Baritone 走过去。
     *
     * <p>每次调用都会先检查上一个目标的进展：捡到了就清状态，Baritone 放弃或超时
     * 则把该位置拉黑，下次自动换一个目标。
     *
     * @param item   要拾取的方块对应的物品
     * @param radius 搜索半径（方块）
     */
    public static Status collectNearby(Item item, int radius) {
        if (!isAvailable()) return Status.UNAVAILABLE;
        if (mc.player == null || mc.level == null || item == null) return Status.UNAVAILABLE;

        // 先结算上一个目标的状态
        if (currentTarget != null) {
            Progress progress = checkProgress();
            if (progress == Progress.IN_PROGRESS) return Status.ALREADY_PATHING;
            if (progress == Progress.FAILED) {
                unreachable.add(currentTarget);
            }
            clearTarget();
        }

        List<ItemEntity> candidates = findDrops(item, radius);
        if (candidates.isEmpty()) return Status.NO_ITEM_NEARBY;

        ItemEntity target = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity e : candidates) {
            if (unreachable.contains(e.blockPosition())) continue;
            double d = e.distanceToSqr(mc.player);
            if (d < best) {
                best = d;
                target = e;
            }
        }

        // 附近确实有东西，但全都试过且到不了
        if (target == null) return Status.ALL_UNREACHABLE;

        try {
            disableBreakAndPlace();
            // 走到掉落物附近即可，剩下的靠拾取判定，不需要精确站上去
            setGoalNear(target.blockPosition(), 1);

            currentTarget = target.blockPosition();
            targetStartedAt = System.currentTimeMillis();
            return Status.PATHING;
        } catch (Throwable t) {
            return Status.UNAVAILABLE;
        }
    }

    /** 上一个目标的进展。 */
    private enum Progress {
        /** 仍在前往。 */
        IN_PROGRESS,
        /** 目标已消失，通常意味着捡到了。 */
        DONE,
        /** Baritone 放弃或超时，判定为到不了。 */
        FAILED
    }

    private static Progress checkProgress() {
        // 目标掉落物已经不在了，多半是被捡走（或被别人捡了），都算完成
        if (!dropStillThere(currentTarget)) return Progress.DONE;

        boolean pathing = isPathing();

        // Baritone 已经不再寻路，但目标还在原地，说明它放弃了
        if (!pathing) return Progress.FAILED;

        // 还在走，但超时了也判定失败，避免卡在一个永远到不了的目标上
        if (System.currentTimeMillis() - targetStartedAt > TARGET_TIMEOUT_MS) {
            return Progress.FAILED;
        }

        return Progress.IN_PROGRESS;
    }

    /** 指定位置附近是否还有掉落物。 */
    private static boolean dropStillThere(BlockPos pos) {
        if (mc.level == null) return false;

        AABB box = new AABB(pos).inflate(1.5);
        return !mc.level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive()).isEmpty();
    }

    /** 停止 Baritone 寻路，并恢复之前改动过的设置。 */
    public static void stop() {
        if (!isAvailable()) return;

        try {
            Object baritone = primaryBaritone();
            Object process = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
            process.getClass().getMethod("setGoal", Class.forName("baritone.api.pathing.goals.Goal"))
                .invoke(process, (Object) null);

            Object pathingBehavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
        } catch (Throwable ignored) {
            // Baritone 内部结构变动时不影响刷取主流程
        }

        clearTarget();
        restoreBreakAndPlace();
    }

    /** 清空不可达记录，在模块重新启用时调用。 */
    public static void reset() {
        unreachable.clear();
        clearTarget();
    }

    /** 当前有多少个位置被判定为到不了。 */
    public static int unreachableCount() {
        return unreachable.size();
    }

    private static void clearTarget() {
        currentTarget = null;
        targetStartedAt = 0;
    }

    /** Baritone 当前是否正在寻路。 */
    public static boolean isPathing() {
        if (!isAvailable()) return false;
        try {
            Object baritone = primaryBaritone();
            Object pathingBehavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);

            boolean pathing = (Boolean) pathingBehavior.getClass()
                .getMethod("isPathing").invoke(pathingBehavior);
            if (pathing) return true;

            // 还在算路也算「在忙」，否则刚下令的那一两 tick 会被误判成放弃
            Object inProgress = pathingBehavior.getClass()
                .getMethod("getInProgress").invoke(pathingBehavior);
            if (inProgress instanceof java.util.Optional<?> opt) return opt.isPresent();

            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<ItemEntity> findDrops(Item item, int radius) {
        if (mc.player == null || mc.level == null) return new ArrayList<>();

        AABB box = mc.player.getBoundingBox().inflate(radius);
        return mc.level.getEntitiesOfClass(ItemEntity.class, box,
            e -> e.isAlive() && e.getItem().getItem() == item);
    }

    private static Object primaryBaritone() throws Exception {
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Object provider = api.getMethod("getProvider").invoke(null);
        return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
    }

    private static void setGoalNear(BlockPos pos, int range) throws Exception {
        Object baritone = primaryBaritone();
        Object process = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);

        Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
        Object goal = goalNear.getConstructor(BlockPos.class, int.class).newInstance(pos, range);

        process.getClass()
            .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"))
            .invoke(process, goal);
    }

    /**
     * 强制关闭 Baritone 的挖掘与放置，并记下原值以便恢复。
     *
     * <p>这是「绝不破坏或放置方块」的实际保证：Baritone 默认会为了抵达目标而挖穿墙壁
     * 或搭桥，在刷取场景里那会毁掉玩家搭的封闭空间。
     */
    private static void disableBreakAndPlace() throws Exception {
        Object settings = Class.forName("baritone.api.BaritoneAPI")
            .getMethod("getSettings").invoke(null);

        if (previousAllowBreak == null) {
            previousAllowBreak = (Boolean) readSetting(settings, "allowBreak");
            previousAllowPlace = (Boolean) readSetting(settings, "allowPlace");
        }

        writeSetting(settings, "allowBreak", false);
        writeSetting(settings, "allowPlace", false);
    }

    private static void restoreBreakAndPlace() {
        if (previousAllowBreak == null) return;

        try {
            Object settings = Class.forName("baritone.api.BaritoneAPI")
                .getMethod("getSettings").invoke(null);
            writeSetting(settings, "allowBreak", previousAllowBreak);
            writeSetting(settings, "allowPlace", previousAllowPlace);
        } catch (Throwable ignored) {
            // 恢复失败不影响刷取，但下次仍会重新关掉
        } finally {
            previousAllowBreak = null;
            previousAllowPlace = null;
        }
    }

    private static Object readSetting(Object settings, String name) throws Exception {
        Object setting = settings.getClass().getField(name).get(settings);
        return setting.getClass().getField("value").get(setting);
    }

    private static void writeSetting(Object settings, String name, Object value) throws Exception {
        Object setting = settings.getClass().getField(name).get(settings);
        setting.getClass().getField("value").set(setting, value);
    }
}
