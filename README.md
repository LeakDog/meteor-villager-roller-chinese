
# Meteor Villager Roller 汉化版（村民刷附魔）

![checks](https://github.com/LeakDog/meteor-villager-roller-chinese/actions/workflows/checks.yml/badge.svg)
![release](https://github.com/LeakDog/meteor-villager-roller-chinese/actions/workflows/release.yml/badge.svg)

反复重置村民职业，直到刷出想要的附魔书。这是 [Meteor Client](https://github.com/MeteorDevelopment/meteor-client)
的附属模块，面向在服务器上刷图书管理员附魔的场景。

在汉化之外，重写了提醒逻辑，新增自动锁定交易与 QQ 推送，挂机刷附魔时不必盯着屏幕。

## 安装

需要 Minecraft 26.2、对应版本的 Meteor Client 和 Java 25。从
[Releases](https://github.com/LeakDog/meteor-villager-roller-chinese/releases) 下载 jar 放进
`mods` 目录即可。

> 与原版 Meteor Villager Roller 互斥（已在 `fabric.mod.json` 声明 `breaks`），只能装一个。

## 字体限制（请先读这一段）

Meteor 用自绘字体渲染自己的界面，而该字体**不含中日韩字形**。这是 Meteor 本身的限制。

- **设置界面的中文显示为空白** —— 在 Meteor 设置里关掉 `custom-font` 切回原版字体即可正常显示。
- **聊天消息和屏幕通知不受影响** —— 这两条路径走原版字体渲染，中文始终正常。

设置项的内部名称（`name`）保持英文未改，因为 Meteor 的配置以这些名称为键存取。

## 使用方法

1. 准备一个村民（不能是傻子）和若干讲台
2. 把自己和村民关在封闭空间里，用半砖、方块、楼梯限制它走动（**不要用活板门**，会干扰寻路）
3. 在村民能够到的位置放一个讲台，确认手动换职业可行
4. 配置模块并启用，按聊天提示依次选择方块和村民
5. 让模块自己跑

建议带把斧头，品质越好刷取越快。

## 提醒

- 音效列表里的全部音效都会播放，可用 `sound-repeat-count` 和 `sound-repeat-delay`
  设置重复轮数与间隔，避免一次性提示被错过
- `show-toast` 弹出屏幕通知，切出游戏窗口后回来仍能看到
- 提示音由独立调度器播放，模块关闭甚至断线回到主菜单后，剩余轮数依然会播完
- 所有提醒先于模块关闭和自动断线执行，开启 `disconnect-when-found` 时也收得到

## 自动锁定交易（默认关闭）

村民**首次被交易后职业就永久固定**，之后破坏工作方块也不会重置。所以刷到目标附魔时立刻
买下这本书就能把结果保住。开启 `lock-trade` 后会自动用背包里的物品完成这笔交易，
**只成交一次**。

| 设置 | 说明 |
|------|------|
| `lock-trade` | 总开关，默认关闭（会消耗你的绿宝石和书） |
| `lock-trade-retry` | 锁定失败时保持模块开启并停在交易界面，便于补货后手动完成 |

交易前会检查是否售罄、物品是否够付（不足时报出缺口）、背包是否有空格。锁定成功后会保留
交易界面不自动关闭，因为立刻关容器会打断服务端的取物处理。

## 自动补货

`Restock` 分组。两项都**不会破坏或放置任何方块**。

| 设置 | 说明 | 默认 |
|------|------|------|
| `auto-refill-hotbar` | 快捷栏的工作方块用完时，自动从背包拿一组补上 | 开启 |
| `auto-collect-drops` | 背包存量低于阈值时，调用 Baritone 走到附近掉落的方块旁自动捡起 | 关闭 |
| `collect-threshold` | 背包存量低于此数量才去捡 | 4 |
| `collect-radius` | 拾取搜索半径（方块） | 16 |

补货只在物品栏内搬运。拾取需要安装 Baritone，走的是「寻路到掉落物附近」再靠碰撞拾取，
不使用 Baritone 自带的挖掘功能。寻路前会强制关闭 Baritone 的 `allowBreak` 与 `allowPlace`，
模块停止时恢复原值 —— 否则它会为了抵达目标挖穿你搭的封闭空间。

## OneBot / QQ 推送（默认关闭）

通过 [OneBot v11](https://github.com/botuniverse/onebot-11) 协议把刷取过程中的事件推送为
QQ 消息，可对接 [SnowLuma](https://github.com/SnowLuma/SnowLuma)、NapCat 等实现。

| 设置 | 说明 |
|------|------|
| `onebot-enabled` | 总开关 |
| `onebot-url` | HTTP 服务地址，如 `http://127.0.0.1:3000` |
| `onebot-token` | access token，留空表示无鉴权 |
| `onebot-message-type` | `Private` 私聊或 `Group` 群聊 |
| `onebot-target-id` | QQ 号或群号，纯数字 |

### 消息类别

每类事件各有一个消息模板，**模板留空即不推送该类通知**。默认只开启找到附魔与锁定相关的
几类，避免挂机时被无关消息打扰。

| 设置 | 事件 | 默认 | 限流 |
|------|------|------|------|
| `onebot-msg-found` | 刷出目标附魔 | 启用 | 无 |
| `onebot-msg-trade-locked` | 锁定成功 | 启用 | 无 |
| `onebot-msg-trade-failed` | 锁定失败 | 启用 | 30 秒 |
| `onebot-msg-trade-insufficient` | 物品不足 | 启用 | 30 秒 |
| `onebot-msg-error` | 运行错误中止 | 启用 | 30 秒 |
| `onebot-msg-started` / `onebot-msg-stopped` | 开始 / 停止刷取 | 留空 | 无 |
| `onebot-msg-place-failed` | 放置失败 | 留空 | 1 分钟 |
| `onebot-msg-anomaly` | 状态异常（反作弊撤销放置等） | 留空 | 1 分钟 |
| `onebot-msg-profession-timeout` | 村民未按时接受职业 | 留空 | 1 分钟 |

### @ 提醒（仅群聊）

`onebot-at-targets` 填要 @ 的 QQ 号，多个用逗号分隔；填 `all` 表示 @全体成员。留空则不 @。
非数字的条目会被忽略。

填好后会出现每类事件的 `onebot-at-*` 开关，可单独决定哪类消息带 @。默认只在找到附魔、
锁定成功/失败、物品不足这几类需要立刻知晓的事件上 @，异常类默认不 @ 以免反复打扰。

> @全体成员需要机器人账号是群管理员或群主，且有每日次数限制（通常 10 次）。
> 不满足时服务端会拒绝，模块会把失败原因报到聊天里。

占位符：`{player}`、`{server}` 全类可用；`{enchant}`、`{level}`、`{price}` 用于找到附魔与锁定；
`{cost}` 为实际支付；`{reason}` 为失败原因。例如：

```
{player} 在 {server} 刷到了 {enchant} {level} 级，只要 {price} 个绿宝石
```

写错的占位符会原样保留，方便发现拼错。点**预览消息模板**可在聊天里查看渲染结果，不会真的推送。

异常类事件可能每 tick 触发，若不限流会瞬间刷爆消息甚至触发风控，所以这些类别有最小发送
间隔，同类别内容重复时也会被抑制。

### 对接 SnowLuma

SnowLuma 默认自带一个 `127.0.0.1:3000`、路径 `/` 的 HTTP 服务端，并在首次启动时**随机生成
access token**。所以通常只需：

1. 在 WebUI（默认 `http://localhost:5099`）完成 QQ 接入
2. 确认 `http-default` 处于启用状态，复制它的 accessToken
3. `onebot-url` 填 `http://127.0.0.1:3000`，`onebot-token` 粘贴该值

token 不能留空，否则一律返回 401。若改过 `path`，`onebot-url` 要带上（如
`http://127.0.0.1:3000/onebot`）。

配置好后点**测试连接并发送 ping**：先验证地址与 token，再向目标发一条测试消息，
便于区分是凭证问题还是目标 ID 问题。

### 安全须知

- 这是**出网行为**，会把附魔信息发到你自己配置的端点。默认关闭。
- `onebot-token` 会随 Meteor 配置以**明文**保存在本地，但不会出现在任何聊天或日志中。
- 建议只填本机回环地址。填公网 `http://` 地址时 token 与消息均为明文传输。
- 请求异步执行，网络故障只报一条聊天错误，不影响刷取。

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/`。改动版本号后需要先 `clean`，否则资源处理会命中缓存。

## 来源与致谢

本项目基于 [maxsupermanhd/meteor-villager-roller](https://github.com/maxsupermanhd/meteor-villager-roller)
开发，原始刷取逻辑由 **FlexCoral**、**seasnail8169** 和 **Cloudburst** 编写。

按照原项目要求：将 Villager Roller 集成进其他客户端时，若未修改底层功能或代码，
需事先取得同意或在模块设置/描述中保留署名。

## 许可证

GPL-3.0，见项目根目录的 LICENSE。
