package maxsuperman.addons.roller.modules;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import maxsuperman.addons.roller.gui.screens.EnchantmentSelectScreen;
import maxsuperman.addons.roller.utils.OneBotNotifier;
import maxsuperman.addons.roller.utils.RollNotifier;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class VillagerRoller extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSound = settings.createGroup("Sound");
    private final SettingGroup sgChatFeedback = settings.createGroup("Chat feedback", false);
    private final SettingGroup sgOneBot = settings.createGroup("OneBot", false);

    private final Setting<Boolean> disableIfFound = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-found")
        .description("找到后把该附魔从列表中禁用")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disconnectIfFound = sgGeneral.add(new BoolSetting.Builder()
        .name("disconnect-when-found")
        .description("找到列表中的附魔后自动断开服务器连接")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> saveListToConfig = sgGeneral.add(new BoolSetting.Builder()
        .name("save-list-to-config")
        .description("是否把目标附魔列表保存到配置并随配置一起加载")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enablePlaySound = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-sound")
        .description("找到目标交易时播放提示音")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<SoundEvent>> sound = sgSound.add(new SoundEventListSetting.Builder()
        .name("sound-to-play")
        .description("找到目标交易时播放的音效，列表中的音效每轮都会全部播放一遍")
        .defaultValue(Collections.singletonList(SoundEvents.AMETHYST_CLUSTER_BREAK))
        .build()
    );

    private final Setting<Double> soundPitch = sgSound.add(new DoubleSetting.Builder()
        .name("sound-pitch")
        .description("提示音的音调")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Double> soundVolume = sgSound.add(new DoubleSetting.Builder()
        .name("sound-volume")
        .description("提示音的音量")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<Integer> soundRepeatCount = sgSound.add(new IntSetting.Builder()
        .name("sound-repeat-count")
        .description("提示音重复播放的轮数，避免玩家离开时错过一次性提示")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> soundRepeatDelay = sgSound.add(new IntSetting.Builder()
        .name("sound-repeat-delay")
        .description("每轮提示音之间的间隔（tick，20 tick = 1 秒）")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Boolean> showToast = sgGeneral.add(new BoolSetting.Builder()
        .name("show-toast")
        .description("找到目标交易时弹出通知，切出游戏窗口后回来也能看到")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnScreen = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-screens")
        .description("有任何界面打开时暂停刷取")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> headRotateOnPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate-place")
        .description("放置方块时是否转头看向该方块")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> failedToPlaceDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-fail-delay")
        .description("放置失败后的提示间隔（毫秒）")
        .defaultValue(1500)
        .min(0)
        .sliderRange(0, 10000)
        .build()
    );

    private final Setting<Boolean> failedToPlaceDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("place-fail-disable")
        .description("方块放置失败时关闭模块")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxProfessionWaitTime = sgGeneral.add(new IntSetting.Builder()
        .name("max-profession-wait-time")
        .description("村民不接受职业时的等待上限（毫秒），0 表示无限等待")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10000)
        .build()
    );

    private final Setting<Boolean> onlyTradeable = sgGeneral.add(new BoolSetting.Builder()
        .name("only-tradeable")
        .description("只显示可交易的附魔。村民不卖灵魂疾行、迅捷潜行和风爆，关掉后「添加全部」会把它们也加进来。若服务器用插件添加了自定义附魔，需要关掉此项")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortEnchantments = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-enchantments")
        .description("按名称排序显示附魔")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> instantRebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("CivBreak")
        .description("使用 CivBreak 瞬间挖掉刷取方块，建议站在该方块正上方")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> interactRetry = sgGeneral.add(new IntSetting.Builder()
        .name("interact-retry")
        .description("服务器未确认与村民交互的数据包时，经过多少 tick 后重发一次，0 表示不重发")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> cfSetup = sgChatFeedback.add(new BoolSetting.Builder()
        .name("setup")
        .description("开始时的操作引导提示（关闭后仅在模块列表的状态里显示）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfPausedOnScreen = sgChatFeedback.add(new BoolSetting.Builder()
        .name("paused-on-screen")
        .description("提示「刷取已暂停，与村民交互以继续」")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfLowerLevel = sgChatFeedback.add(new BoolSetting.Builder()
        .name("found-lower-level")
        .description("提示找到了附魔但等级不达要求")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfTooExpensive = sgChatFeedback.add(new BoolSetting.Builder()
        .name("found-too-expensive")
        .description("提示找到了附魔但价格超出上限")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfIgnored = sgChatFeedback.add(new BoolSetting.Builder()
        .name("found-not-on-the-list")
        .description("提示找到了附魔但它不在列表中")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfProfessionTimeout = sgChatFeedback.add(new BoolSetting.Builder()
        .name("profession-timeout")
        .description("提示村民在规定时间内没有接受职业")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfPlaceFailed = sgChatFeedback.add(new BoolSetting.Builder()
        .name("place-failed")
        .description("提示放置失败、无法放置或快捷栏里取不到刷取方块（这些情况仍会触发放置失败相关设置）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfDiscrepancy = sgChatFeedback.add(new BoolSetting.Builder()
        .name("discrepancy")
        .description("提示模块进入了预期之外的状态（通常是反作弊导致）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfSentRetryInteract = sgChatFeedback.add(new BoolSetting.Builder()
        .name("sent-retry-interact")
        .description("提示服务器丢弃了初始交互数据包并已重发")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfBlockPlaceBounce = sgChatFeedback.add(new BoolSetting.Builder()
        .name("block-place-bounce")
        .description("提示放置被瞬间撤销后又重新放上")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cfFoundMatching = sgChatFeedback.add(new BoolSetting.Builder()
        .name("found-matching")
        .description("停止前告知找到了什么")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onebotEnabled = sgOneBot.add(new BoolSetting.Builder()
        .name("onebot-enabled")
        .description("找到目标附魔后通过 OneBot v11 推送 QQ 通知，可对接 SnowLuma、NapCat 等实现。这是出网行为，默认关闭")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> onebotUrl = sgOneBot.add(new StringSetting.Builder()
        .name("onebot-url")
        .description("OneBot HTTP 服务地址。建议使用本机回环地址，填公网 http 地址时凭证与消息均为明文传输")
        .defaultValue("http://127.0.0.1:3000")
        .visible(onebotEnabled::get)
        .build()
    );

    private final Setting<String> onebotToken = sgOneBot.add(new StringSetting.Builder()
        .name("onebot-token")
        .description("OneBot access token，留空表示未设置鉴权。该凭证会随 Meteor 配置以明文保存，且不会出现在任何聊天或日志中")
        .defaultValue("")
        .visible(onebotEnabled::get)
        .build()
    );

    private final Setting<OneBotNotifier.MessageType> onebotMessageType = sgOneBot.add(new EnumSetting.Builder<OneBotNotifier.MessageType>()
        .name("onebot-message-type")
        .description("推送到私聊还是群聊")
        .defaultValue(OneBotNotifier.MessageType.Group)
        .visible(onebotEnabled::get)
        .build()
    );

    private final Setting<String> onebotTargetId = sgOneBot.add(new StringSetting.Builder()
        .name("onebot-target-id")
        .description("接收通知的 QQ 号（私聊）或群号（群聊），必须是纯数字")
        .defaultValue("")
        .visible(onebotEnabled::get)
        .build()
    );

    private enum State {
        DISABLED,
        WAITING_FOR_TARGET_BLOCK,
        WAITING_FOR_TARGET_VILLAGER,
        ROLLING_BREAKING_BLOCK,
        ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR,
        ROLLING_PLACING_BLOCK,
        ROLLING_WAITING_FOR_VILLAGER_PROFESSION_NEW,
        ROLLING_WAITING_FOR_VILLAGER_TRADES
    }

    private static final Path CONFIG_PATH = MeteorClient.FOLDER.toPath().resolve("VillagerRoller");
    private State currentState = State.DISABLED;
    private Villager rollingVillager;
    private BlockPos rollingBlockPos;
    private Block rollingBlock;
    private final List<RollingEnchantment> searchingEnchants = new ArrayList<>();
    private long failedToPlacePrevMsg = System.currentTimeMillis();
    private long currentProfessionWaitTime;

    public VillagerRoller() {
        super(Categories.Misc, "villager-roller", "反复重置村民职业，直到刷出想要的附魔。");
    }

    @Override
    public void onActivate() {
        if (toggleOnBindRelease) {
            toggleOnBindRelease = false;
            if (cfSetup.get()) {
                warning("你把「Toggle on bind release」设成了开启，已自动关掉，省去你排查问题的功夫");
            }
        }
        // 玩家重新启用模块时，停掉上一次还没播完的提示音
        RollNotifier.get().stop();
        currentState = State.WAITING_FOR_TARGET_BLOCK;
        if (cfSetup.get()) {
            info("攻击你想用来刷取的方块（通常是讲台）");
            warnAboutCustomFont();
        }
    }

    @Override
    public void onDeactivate() {
        currentState = State.DISABLED;
    }

    @Override
    public String getInfoString() {
        return switch (currentState) {
            case DISABLED -> "已关闭";
            case WAITING_FOR_TARGET_BLOCK -> "等待选择方块";
            case WAITING_FOR_TARGET_VILLAGER -> "等待选择村民";
            case ROLLING_BREAKING_BLOCK -> "正在破坏方块";
            case ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR -> "等待职业清除";
            case ROLLING_PLACING_BLOCK -> "正在放置方块";
            case ROLLING_WAITING_FOR_VILLAGER_PROFESSION_NEW -> "等待获得新职业";
            case ROLLING_WAITING_FOR_VILLAGER_TRADES -> "等待交易列表";
        };
    }

    /**
     * Meteor 的自绘字体只烘焙了 ASCII、拉丁、希腊和西里尔字形，不含中日韩字符，
     * 因此设置界面里的中文在默认字体下会显示为空白。聊天和通知走的是原版字体渲染，不受影响。
     */
    private void warnAboutCustomFont() {
        if (Config.get().customFont.get()) {
            info("提示：Meteor 自定义字体不含中文字形，设置界面的中文会显示为空白。在 Meteor 设置里关掉 custom-font 即可正常显示（聊天与通知不受影响）");
        }
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = super.toTag();
        if (saveListToConfig.get()) {
            ListTag l = new ListTag();
            for (RollingEnchantment e : searchingEnchants) {
                l.add(e.toTag());
            }
            tag.put("rolling", l);
        }
        return tag;
    }

    @Override
    public Module fromTag(CompoundTag tag) {
        super.fromTag(tag);
        if (saveListToConfig.get()) {
            ListTag l = tag.getListOrEmpty("rolling");
            searchingEnchants.clear();
            for (Tag e : l) {
                if (e.getId() != Tag.TAG_COMPOUND) {
                    info("列表中存在无效元素");
                    continue;
                }
                searchingEnchants.add(new RollingEnchantment().fromTag((CompoundTag) e));
            }
        }
        return this;
    }

    private boolean loadSearchingFromFile(File f) {
        if (!f.exists() || !f.canRead()) {
            error("文件不存在或无法读取");
            return false;
        }
        CompoundTag r = null;
        try {
            r = NbtIo.read(f.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (r == null) {
            error("无法从文件中读取 NBT 数据");
            return false;
        }
        ListTag l = r.getListOrEmpty("rolling");
        searchingEnchants.clear();
        for (Tag e : l) {
            if (e.getId() != Tag.TAG_COMPOUND) {
                error("列表中存在无效元素");
                return false;
            }
            searchingEnchants.add(new RollingEnchantment().fromTag((CompoundTag) e));
        }
        return true;
    }

    public boolean saveSearchingToFile(File f) {
        ListTag l = new ListTag();
        for (RollingEnchantment e : searchingEnchants) {
            l.add(e.toTag());
        }
        CompoundTag c = new CompoundTag();
        c.put("rolling", l);
        if (Files.notExists(f.getParentFile().toPath()) && !f.getParentFile().mkdirs()) {
            error("创建目录失败");
            return false;
        }
        try {
            NbtIo.write(c, f.toPath());
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        fillWidget(theme, list);
        return list;
    }

    private void fillWidget(GuiTheme theme, WVerticalList list) {
        if (onebotEnabled.get()) {
            WSection onebotSection = list.add(theme.section("OneBot 连接")).expandX().widget();
            WButton testConnection = onebotSection.add(theme.button("测试连接并发送 ping")).expandX().widget();
            testConnection.tooltip = "先验证服务地址与 token，再向配置的目标发送一条 ping 测试消息";
            testConnection.action = () -> OneBotNotifier.test(oneBotTarget(), this::info, this::error);
        }

        WSection loadDataSection = list.add(theme.section("配置保存")).expandX().widget();

        WTable control = loadDataSection.add(theme.table()).expandX().widget();

        WTextBox savedConfigName = control.add(theme.textBox("default")).expandWidgetX().expandCellX().expandX().widget();
        WButton save = control.add(theme.button("保存")).expandX().widget();
        save.action = () -> {
            if (saveSearchingToFile(new File(new File(MeteorClient.FOLDER, "VillagerRoller"), savedConfigName.get() + ".nbt"))) {
                info("保存成功");
            } else {
                error("保存失败");
            }
            list.clear();
            fillWidget(theme, list);
        };
        control.row();

        ArrayList<String> configs = new ArrayList<>();
        if (Files.notExists(CONFIG_PATH)) {
            if (!CONFIG_PATH.toFile().mkdirs()) error("创建目录失败 [{}]", CONFIG_PATH);
        } else {
            try (DirectoryStream<Path> configDir = Files.newDirectoryStream(CONFIG_PATH)) {
                for (Path config : configDir) {
                    configs.add(FilenameUtils.removeExtension(config.getFileName().toString()));
                }
            } catch (IOException e) {
                error("列出目录失败", e);
            }
        }
        if (!configs.isEmpty()) {
            WDropdown<String> loadedConfigName = control.add(theme.dropdown(configs.toArray(new String[0]), "default")).expandWidgetX().expandCellX().expandX().widget();
            WButton load = control.add(theme.button("加载")).expandX().widget();
            load.action = () -> {
                if (loadSearchingFromFile(new File(new File(MeteorClient.FOLDER, "VillagerRoller"), loadedConfigName.get() + ".nbt"))) {
                    list.clear();
                    fillWidget(theme, list);
                    info("加载成功");
                } else {
                    error("加载文件失败。");
                }
            };
        }

        WSection enchantments = list.add(theme.section("附魔列表")).expandX().widget();

        WTable table = enchantments.add(theme.table()).expandX().widget();
        table.add(theme.item(Items.BOOK.getDefaultInstance()));
        table.add(theme.label("附魔"));
        table.add(theme.label("等级"));
        table.add(theme.label("价格"));
        table.add(theme.label("启用"));
        table.add(theme.label("移除"));
        table.row();
        // 无论是否排序都要剔除无效条目：Identifier.tryParse 在配置损坏时会返回 null，
        // 之前只在排序开启时清理，关掉排序就会在下面 e.enchantment.toString() 处抛 NPE
        searchingEnchants.removeIf(ench -> ench.enchantment == null);
        if (sortEnchantments.get()) {
            searchingEnchants.sort(Comparator.comparing(o -> o.enchantment));
        }

        Optional<Registry<Enchantment>> reg;
        if (mc.level != null) {
            reg = mc.level.registryAccess().lookup(Registries.ENCHANTMENT);
        } else {
            reg = Optional.empty();
        }

        for (int i = 0; i < searchingEnchants.size(); i++) {
            RollingEnchantment e = searchingEnchants.get(i);
            Optional<Holder.Reference<Enchantment>> en;
            if (reg.isPresent()) {
                en = reg.get().get(e.enchantment);
            } else {
                en = Optional.empty();
            }
            final int si = i;
            ItemStack book = Items.ENCHANTED_BOOK.getDefaultInstance();
            int maxlevel = 255;
            if (en.isPresent()) {
                book = EnchantmentHelper.createBook(new EnchantmentInstance(en.get(), en.get().value().getMaxLevel()));
                maxlevel = en.get().value().getMaxLevel();
            }
            table.add(theme.item(book));

            WHorizontalList label = theme.horizontalList();
            WButton c = label.add(theme.button("更改")).widget();
            c.action = () -> mc.gui.setScreen(new EnchantmentSelectScreen(theme, onlyTradeable.get(), sel -> {
                searchingEnchants.set(si, sel);
                list.clear();
                fillWidget(theme, list);
            }));
            if (en.isPresent()) {
                label.add(theme.label(Names.get(en.get())));
            } else {
                label.add(theme.label(e.enchantment.toString()));
            }
            table.add(label);

            WIntEdit lev = table.add(theme.intEdit(e.minLevel, 0, maxlevel, true)).minWidth(40).expandX().widget();
            lev.action = () -> e.minLevel = lev.get();
            lev.tooltip = "最低附魔等级，填 0 表示只接受最高等级（自定义附魔时 0 等同于 1）";

            WHorizontalList costbox = table.add(theme.horizontalList()).minWidth(50).expandX().widget();
            WIntEdit cost = costbox.add(theme.intEdit(e.maxCost, 0, 64, false)).minWidth(40).expandX().widget();
            cost.action = () -> e.maxCost = cost.get();
            cost.tooltip = "最高价格（绿宝石），0 表示不限制";

            WButton setOptimal = costbox.add(theme.button("O")).widget();
            setOptimal.tooltip = "设为最优价格 2 + 最高等级×3（宝藏附魔翻倍，已知时生效）";
            setOptimal.action = () -> {
                list.clear();
                en.ifPresent(enchantmentReference -> e.maxCost = getMinimumPrice(enchantmentReference));
                fillWidget(theme, list);
            };

            WCheckbox enabled = table.add(theme.checkbox(e.enabled)).widget();
            enabled.action = () -> e.enabled = enabled.checked;
            enabled.tooltip = "是否启用";

            WMinus del = table.add(theme.minus()).widget();
            del.action = () -> {
                list.clear();
                searchingEnchants.remove(e);
                fillWidget(theme, list);
            };
            table.row();
        }

        WTable controls = list.add(theme.table()).expandX().widget();

        WButton removeAll = controls.add(theme.button("全部移除")).expandX().widget();
        removeAll.action = () -> {
            list.clear();
            searchingEnchants.clear();
            fillWidget(theme, list);
        };

        WButton add = controls.add(theme.button("添加")).expandX().widget();
        add.action = () -> mc.gui.setScreen(new EnchantmentSelectScreen(theme, onlyTradeable.get(), e -> {
            e.minLevel = 1;
            e.maxCost = 64;
            e.enabled = true;
            searchingEnchants.add(e);
            list.clear();
            fillWidget(theme, list);
        }));

        WButton addAll = controls.add(theme.button("添加全部")).expandX().widget();
        addAll.action = () -> {
            list.clear();
            searchingEnchants.clear();
            if (reg.isPresent()) {
                for (Holder<Enchantment> e : getEnchants(onlyTradeable.get())) {
                    searchingEnchants.add(new RollingEnchantment(reg.get().getKey(e.value()), e.value().getMaxLevel(), getMinimumPrice(e), true));
                }
            }
            fillWidget(theme, list);
        };
        controls.row();

        WButton setOptimalForAll = controls.add(theme.button("全部设为最优价")).expandX().widget();
        setOptimalForAll.action = () -> {
            list.clear();
            if (reg.isPresent()) {
                for (RollingEnchantment e : searchingEnchants) {
                    reg.get().get(e.enchantment).ifPresent(enchantmentReference -> e.maxCost = getMinimumPrice(enchantmentReference));
                }
            }
            fillWidget(theme, list);
        };

        WButton priceBumpUp = controls.add(theme.button("全部价格 +1")).expandX().widget();
        priceBumpUp.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                if (e.maxCost < 64) e.maxCost++;
            }
            fillWidget(theme, list);
        };

        WButton priceBumpDown = controls.add(theme.button("全部价格 -1")).expandX().widget();
        priceBumpDown.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                if (e.maxCost > 0) e.maxCost--;
            }
            fillWidget(theme, list);
        };
        controls.row();

        WButton setZeroForAll = controls.add(theme.button("全部价格设为 0")).expandX().widget();
        setZeroForAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.maxCost = 0;
            }
            fillWidget(theme, list);
        };

        WButton enableAll = controls.add(theme.button("全部启用")).expandX().widget();
        enableAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.enabled = true;
            }
            fillWidget(theme, list);
        };

        WButton disableAll = controls.add(theme.button("全部禁用")).expandX().widget();
        disableAll.action = () -> {
            list.clear();
            for (RollingEnchantment e : searchingEnchants) {
                e.enabled = false;
            }
            fillWidget(theme, list);
        };
        controls.row();

    }

    public List<Holder<Enchantment>> getEnchants(boolean onlyTradeable) {
        if (mc.level == null) {
            return Collections.emptyList();
        }
        var reg = mc.level.registryAccess().lookup(Registries.ENCHANTMENT);
        if (reg.isEmpty()) {
            return Collections.emptyList();
        }
        List<Holder<Enchantment>> available = new ArrayList<>();
        if (onlyTradeable) {
            var i = reg.get().getTagOrEmpty(EnchantmentTags.TRADEABLE);
            i.iterator().forEachRemaining(available::add);
            return available;
        } else {
            for (var a : reg.get().asHolderIdMap()) {
                available.add(a);
            }
            return available;
        }
    }

    public static int getMinimumPrice(Holder<Enchantment> e) {
        if (e == null) return 0;
        return e.is(EnchantmentTags.DOUBLE_TRADE_PRICE) ? (2 + 3 * e.value().getMaxLevel()) * 2 : 2 + 3 * e.value().getMaxLevel();
    }

    private long waitingForTradesTicks = 0;

    public void triggerInteract() {
        if (mc.player == null || mc.gameMode == null || rollingVillager == null) return;

        if (pauseOnScreen.get() && mc.gui.screen() != null) {
            if (cfPausedOnScreen.get()) {
                info("刷取已暂停，与村民交互以继续");
            }
            return;
        }

        Vec3 playerPos = mc.player.getEyePosition();
        Vec3 villagerPos = rollingVillager.getEyePosition();
        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(mc.player, playerPos, villagerPos,
            rollingVillager.getBoundingBox(), Entity::isPickable, playerPos.distanceToSqr(villagerPos));

        // 只发一次。原先在射线未命中或交互未被消费时会立刻再发一个包，
        // 等于同一 tick 内发两次交互，重发逻辑交由 interact-retry 按间隔处理
        mc.gameMode.interact(mc.player, rollingVillager, entityHitResult, InteractionHand.MAIN_HAND);
    }

    public List<Pair<Holder<Enchantment>, Integer>> getEnchants(ItemStack stack) {
        List<Pair<Holder<Enchantment>, Integer>> ret = new ArrayList<>();
        for (var e : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            ret.add(ObjectIntImmutablePair.of(e.getKey(), e.getIntValue()));
        }
        return ret;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (currentState != State.ROLLING_WAITING_FOR_VILLAGER_TRADES) return;
        if (!(event.packet instanceof ClientboundMerchantOffersPacket p)) return;
        mc.executeIfPossible(() -> triggerTradeCheck(p.getOffers()));
    }

    public void triggerTradeCheck(MerchantOffers l) {
        if (mc.level == null || mc.player == null) return;

        // 旧版本服务器经 Via 连接时可能不下发附魔注册表，这里取不到就直接放弃本轮检查，
        // 而不是让 lookupOrThrow 把异常抛进网络线程
        Optional<Registry<Enchantment>> registry = mc.level.registryAccess().lookup(Registries.ENCHANTMENT);
        if (registry.isEmpty()) {
            error("无法读取附魔注册表，本轮交易检查已跳过");
            mc.player.closeContainer();
            currentState = State.ROLLING_BREAKING_BLOCK;
            return;
        }
        Registry<Enchantment> reg = registry.get();

        for (MerchantOffer offer : l) {
            ItemStack sellItem = offer.getResult();
            if (!sellItem.is(Items.ENCHANTED_BOOK) || sellItem.get(DataComponents.STORED_ENCHANTMENTS) == null)
                continue;

            for (Pair<Holder<Enchantment>, Integer> enchant : getEnchants(sellItem)) {
                int enchantLevel = enchant.right();
                Identifier enchantId = reg.getKey(enchant.key().value());
                if (enchantId == null) continue;
                String enchantIdString = enchantId.toString();
                String enchantName = Names.get(enchant.key());

                boolean found = false;
                for (RollingEnchantment e : searchingEnchants) {
                    if (!e.enabled || e.enchantment == null
                        || !e.enchantment.toString().equals(enchantIdString)) continue;
                    found = true;
                    if (e.minLevel <= 0) {
                        int ml = enchant.key().value().getMaxLevel();
                        if (enchantLevel < ml) {
                            if (cfLowerLevel.get()) {
                                info(String.format("找到了附魔 %s，但不是最高等级：最高 %d > 刷出 %d",
                                    enchantName, ml, enchantLevel));
                            }
                            continue;
                        }
                    } else if (e.minLevel > enchantLevel) {
                        if (cfLowerLevel.get()) {
                            info(String.format("找到了附魔 %s，但等级过低：要求 %d > 刷出 %d",
                                enchantName, e.minLevel, enchantLevel));
                        }
                        continue;
                    }
                    if (e.maxCost > 0 && offer.getBaseCostA().getCount() > e.maxCost) {
                        if (cfTooExpensive.get()) {
                            info(String.format("找到了附魔 %s，但价格过高：上限 %s < 实际 %d",
                                enchantName, e.maxCost, offer.getBaseCostA().getCount()));
                        }
                        continue;
                    }
                    if (disableIfFound.get()) e.enabled = false;

                    int price = offer.getBaseCostA().getCount();

                    // 先完成全部提醒，再关闭模块和断线，否则开启自动断线时玩家什么提示都收不到
                    if (cfFoundMatching.get()) {
                        info(String.format("已找到目标附魔 %s（等级 %d），售价 %d 绿宝石，已停止刷取。",
                            enchantName, enchantLevel, price));
                    }
                    notifyFound(enchantName, enchantLevel, price);

                    toggle();

                    if (disconnectIfFound.get()) {
                        String levelText = (enchantLevel > 1 || enchant.key().value().getMaxLevel() > 1) ? " " + enchantLevel : "";
                        String message = String.format(
                            "%s[%s%s%s] 已找到附魔 %s%s%s%s，售价 %s%d%s 绿宝石，已自动断开连接。",
                            ChatFormatting.GRAY,
                            ChatFormatting.GREEN,
                            title,
                            ChatFormatting.GRAY,
                            ChatFormatting.WHITE,
                            enchantName,
                            levelText,
                            ChatFormatting.GRAY,
                            ChatFormatting.WHITE,
                            price,
                            ChatFormatting.GRAY
                        );
                        mc.getConnection().getConnection().disconnect(Component.nullToEmpty(message));
                    }
                    break;
                }
                if (!found && cfIgnored.get()) {
                    info(String.format("找到了附魔 %s，但它不在列表中。", enchantName));
                }
            }
        }

        mc.player.closeContainer();
        currentState = State.ROLLING_BREAKING_BLOCK;
    }

    /**
     * 找到目标附魔后的全部提醒：声音、屏幕通知、QQ 推送。
     *
     * <p>这些都在 {@code toggle()} 之前调用，保证开启自动断线时玩家仍能收到完整提示。
     */
    private void notifyFound(String enchantName, int enchantLevel, int price) {
        if (enablePlaySound.get()) {
            RollNotifier.get().start(
                sound.get(),
                soundVolume.get().floatValue(),
                soundPitch.get().floatValue(),
                soundRepeatCount.get(),
                soundRepeatDelay.get()
            );
        }

        String summary = String.format("%s %d 级，售价 %d 绿宝石", enchantName, enchantLevel, price);

        if (showToast.get()) {
            // Toast 经原版字体渲染，中文可以正常显示
            mc.gui.toastManager().addToast(new MeteorToast.Builder("村民刷附魔")
                .icon(Items.ENCHANTED_BOOK)
                .text(summary)
                .build());
        }

        if (onebotEnabled.get()) {
            OneBotNotifier.send(oneBotTarget(), "【村民刷附魔】已刷出目标附魔：" + summary, this::error);
        }
    }

    /** 收集当前的 OneBot 连接配置。 */
    private OneBotNotifier.Target oneBotTarget() {
        return new OneBotNotifier.Target(
            onebotUrl.get(),
            onebotToken.get(),
            onebotMessageType.get(),
            onebotTargetId.get()
        );
    }

    @EventHandler
    private void onInteractEntity(InteractEntityEvent event) {
        if (currentState != State.WAITING_FOR_TARGET_VILLAGER) return;
        if (!(event.entity instanceof Villager villager)) return;

        rollingVillager = villager;
        currentState = State.ROLLING_BREAKING_BLOCK;
        if (cfSetup.get()) {
            info("已选定目标村民，开始刷取");
        }
        event.cancel();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreakingBlockEvent(StartBreakingBlockEvent event) {
        if (currentState != State.WAITING_FOR_TARGET_BLOCK) return;

        rollingBlockPos = event.blockPos;
        rollingBlock = mc.level.getBlockState(rollingBlockPos).getBlock();
        currentState = State.WAITING_FOR_TARGET_VILLAGER;
        if (instantRebreak.get()) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, rollingBlockPos, Direction.UP));
        }
        if (cfSetup.get()) {
            info("已选定刷取方块，现在与你想刷附魔的村民交互");
        }
    }

    private void placeFailed(String msg) {
        if (failedToPlacePrevMsg + failedToPlaceDelay.get() <= System.currentTimeMillis()) {
            if (cfPlaceFailed.get()) {
                info(msg);
            }
            failedToPlacePrevMsg = System.currentTimeMillis();
        }
        if (failedToPlaceDisable.get()) toggle();
    }

    /**
     * 目标位置上是否已是玩家选定的刷取方块。
     *
     * <p>模块允许选择任意方块，所以这里按 {@link #rollingBlock} 判断，
     * 而不是把讲台写死 —— 否则选了其他方块时状态机会一直判定为「放错方块」。
     */
    private boolean isRollingBlockPresent() {
        if (mc.level == null || rollingBlockPos == null || rollingBlock == null) return false;
        return mc.level.getBlockState(rollingBlockPos).is(rollingBlock);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 切换维度或断线的瞬间这些引用可能已失效，此时直接跳过本 tick
        if (mc.level == null || mc.player == null) return;

        boolean rolling = currentState != State.DISABLED
            && currentState != State.WAITING_FOR_TARGET_BLOCK
            && currentState != State.WAITING_FOR_TARGET_VILLAGER;

        if (rolling) {
            if (rollingBlockPos == null || rollingBlock == null) return;
            // 村民被杀、卸载或换维度后继续刷取没有意义，且会不断空转
            if (rollingVillager == null || !rollingVillager.isAlive()) {
                error("目标村民已不存在，已停止刷取");
                toggle();
                return;
            }
        }

        switch (currentState) {
            case ROLLING_BREAKING_BLOCK -> {
                if (instantRebreak.get()) {
                    mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, rollingBlockPos, Direction.DOWN));
                }
                if (mc.level.getBlockState(rollingBlockPos) == Blocks.AIR.defaultBlockState()) {
                    // info("Block is broken, waiting for villager to clean profession...");
                    currentState = State.ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR;
                } else if (!instantRebreak.get() && !BlockUtils.breakBlock(rollingBlockPos, true)) {
                    error("无法破坏指定的方块");
                    toggle();
                }
            }
            case ROLLING_WAITING_FOR_VILLAGER_PROFESSION_CLEAR -> {
                if (isRollingBlockPresent()) {
                    if (cfDiscrepancy.get()) {
                        info("刷取方块的挖掘被撤销了？");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                rollingVillager.getVillagerData().profession().unwrapKey().ifPresent(profession -> {
                    if (profession == VillagerProfession.NONE) {
                        // info("Profession cleared");
                        currentState = State.ROLLING_PLACING_BLOCK;
                    }
                });
            }
            case ROLLING_PLACING_BLOCK -> {
                if (isRollingBlockPresent()) {
                    if (cfBlockPlaceBounce.get()) {
                        info("方块放置出现了回弹？");
                    }
                    currentState = State.ROLLING_WAITING_FOR_VILLAGER_PROFESSION_NEW;
                    return;
                }
                String blockName = Names.get(rollingBlock);
                FindItemResult item = InvUtils.findInHotbar(rollingBlock.asItem());
                if (!item.found()) {
                    placeFailed("快捷栏中没有" + blockName);
                    return;
                }
                if (!BlockUtils.canPlace(rollingBlockPos, true)) {
                    placeFailed("无法放置" + blockName);
                    return;
                }
                if (!BlockUtils.place(rollingBlockPos, item, headRotateOnPlace.get(), 5)) {
                    placeFailed("放置" + blockName + "失败");
                    return;
                }
                currentState = State.ROLLING_WAITING_FOR_VILLAGER_PROFESSION_NEW;
                if (maxProfessionWaitTime.get() > 0) {
                    currentProfessionWaitTime = System.currentTimeMillis();
                }
            }
            case ROLLING_WAITING_FOR_VILLAGER_PROFESSION_NEW -> {
                if (maxProfessionWaitTime.get() > 0 && (currentProfessionWaitTime + maxProfessionWaitTime.get() <= System.currentTimeMillis())) {
                    if (cfProfessionTimeout.get()) {
                        info("村民在规定时间内没有接受职业");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                if (mc.level.getBlockState(rollingBlockPos) == Blocks.AIR.defaultBlockState()) {
                    if (cfDiscrepancy.get()) {
                        info("方块放置被服务器撤销（可能是反作弊）");
                    }
                    currentState = State.ROLLING_PLACING_BLOCK;
                    return;
                }
                if (!isRollingBlockPresent()) {
                    if (cfDiscrepancy.get()) {
                        info("放上去的方块不对？！");
                    }
                    currentState = State.ROLLING_BREAKING_BLOCK;
                    return;
                }
                rollingVillager.getVillagerData().profession().unwrapKey().ifPresent(profession -> {
                    if (profession != VillagerProfession.NONE) {
                        currentState = State.ROLLING_WAITING_FOR_VILLAGER_TRADES;
                        // 进入等待前清零，否则上一轮残留的计数会让重发逻辑立刻触发
                        waitingForTradesTicks = 0;
                        triggerInteract();
                    }
                });
            }
            case ROLLING_WAITING_FOR_VILLAGER_TRADES -> {
                int retryTicks = interactRetry.get();
                if (retryTicks > 0) {
                    waitingForTradesTicks++;
                    // 达到间隔就重发一次并重新计时。原先在到达阈值后不再清零，
                    // 会导致此后每 tick 都发一个交互包，很容易被服务器判定为异常
                    if (waitingForTradesTicks >= retryTicks) {
                        waitingForTradesTicks = 0;
                        if (cfSentRetryInteract.get()) {
                            info("正在重发一个交互数据包");
                        }
                        triggerInteract();
                    }
                }
            }
            default -> {
                // Wait for another state
            }
        }
    }

    public static class RollingEnchantment implements ISerializable<RollingEnchantment> {
        private Identifier enchantment;
        private int minLevel;
        private int maxCost;
        private boolean enabled;

        public RollingEnchantment(Identifier enchantment, int minLevel, int maxCost, boolean enabled) {
            this.enchantment = enchantment;
            this.minLevel = minLevel;
            this.maxCost = maxCost;
            this.enabled = enabled;
        }

        public RollingEnchantment() {
            enchantment = Identifier.fromNamespaceAndPath("minecraft", "protection");
            minLevel = 0;
            maxCost = 0;
            enabled = false;
        }

        @Override
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("enchantment", enchantment.toString());
            tag.putInt("minLevel", minLevel);
            tag.putInt("maxCost", maxCost);
            tag.putBoolean("enabled", enabled);
            return tag;
        }

        @Override
        public RollingEnchantment fromTag(CompoundTag tag) {
            enchantment = Identifier.tryParse(tag.getStringOr("enchantment", ""));
            minLevel = tag.getIntOr("minLevel", 1);
            maxCost = tag.getIntOr("maxCost", 64);
            enabled = tag.getBooleanOr("enabled", true);
            return this;
        }
    }
}
