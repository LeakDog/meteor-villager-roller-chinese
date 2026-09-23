package leakdog.addons.roller.utils;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 独立于模块生命周期的提示音调度器。
 *
 * <p>模块在找到目标附魔后会调用 {@code toggle()}，而 {@code Module.toggle()} 会把模块从事件总线上
 * 摘下来（unsubscribe），因此模块自身的 tick 回调会立刻停止，无法在模块内部完成"隔若干 tick 再响一次"
 * 的倒计时。这个类自己订阅事件总线，播完全部次数后再自行退订，所以即使模块已经关闭、
 * 甚至已经断开连接回到主菜单，剩余的提示音依然会播完。
 */
public class RollNotifier {
    private static final RollNotifier INSTANCE = new RollNotifier();

    private final List<SoundEvent> sounds = new ArrayList<>();
    private float volume = 1.0f;
    private float pitch = 1.0f;
    private int remaining;
    private int delayTicks = 20;
    private int ticksUntilNext;
    private boolean subscribed;

    private RollNotifier() {}

    public static RollNotifier get() {
        return INSTANCE;
    }

    /**
     * 开始播放提示音。
     *
     * @param sounds     要播放的音效列表，每一轮会把列表里的音效全部播一遍
     * @param volume     音量
     * @param pitch      音调
     * @param count      重复轮数，小于 1 时不播放
     * @param delayTicks 每轮之间的间隔（tick），至少 1
     */
    public void start(List<SoundEvent> sounds, float volume, float pitch, int count, int delayTicks) {
        if (sounds == null || sounds.isEmpty() || count < 1) return;

        this.sounds.clear();
        for (SoundEvent sound : sounds) {
            if (sound != null) this.sounds.add(sound);
        }
        if (this.sounds.isEmpty()) return;

        this.volume = volume;
        this.pitch = pitch;
        this.remaining = count;
        this.delayTicks = Math.max(1, delayTicks);
        this.ticksUntilNext = 0;

        // 第一轮立即播放，剩下的交给 tick 倒计时
        playOnce();
        this.remaining--;

        if (this.remaining > 0) {
            this.ticksUntilNext = this.delayTicks;
            subscribe();
        } else {
            this.sounds.clear();
        }
    }

    /** 停止尚未播完的提示音，例如玩家手动重新启用模块时。 */
    public void stop() {
        remaining = 0;
        sounds.clear();
        unsubscribe();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (remaining <= 0) {
            unsubscribe();
            return;
        }

        if (--ticksUntilNext > 0) return;

        playOnce();
        remaining--;

        if (remaining > 0) {
            ticksUntilNext = delayTicks;
        } else {
            sounds.clear();
            unsubscribe();
        }
    }

    private void playOnce() {
        if (mc.getSoundManager() == null) return;

        for (SoundEvent sound : sounds) {
            // 注意：forUI 的真实签名是 (sound, volume, pitch)，不是 (sound, pitch, volume)。
            mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, volume, pitch));
        }
    }

    private void subscribe() {
        if (subscribed) return;
        MeteorClient.EVENT_BUS.subscribe(this);
        subscribed = true;
    }

    private void unsubscribe() {
        if (!subscribed) return;
        MeteorClient.EVENT_BUS.unsubscribe(this);
        subscribed = false;
    }
}
