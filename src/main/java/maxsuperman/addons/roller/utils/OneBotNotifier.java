package maxsuperman.addons.roller.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 通过 OneBot v11 协议推送 QQ 通知，可对接 SnowLuma、NapCat 等任意 OneBot v11 实现。
 *
 * <p>安全须知：
 * <ul>
 *     <li>这是出网行为，会把附魔信息发到玩家自己配置的端点，默认关闭。</li>
 *     <li>access token 属于凭证，任何情况下都不会写进聊天或日志。</li>
 *     <li>建议只填本机回环地址。若填公网 HTTP 地址，token 与消息内容均为明文传输。</li>
 * </ul>
 *
 * <p>请求一律放到 {@link MeteorExecutor} 线程池执行，因为 {@link Http} 内部是同步的
 * {@code HttpClient}，在主线程调用会卡住游戏。回调经 Meteor 的聊天工具输出，
 * 其内部会用 {@code mc.execute} 切回主线程，因此从线程池里回报是安全的。
 */
public class OneBotNotifier {
    /** OneBot v11 的消息类型。 */
    public enum MessageType {
        /** 私聊，对应 user_id。 */
        Private,
        /** 群聊，对应 group_id。 */
        Group
    }

    /** 一次请求所需的全部连接参数。 */
    public record Target(String baseUrl, String token, MessageType type, String targetId) {}

    private OneBotNotifier() {}

    /**
     * 异步推送一条文本消息。任何失败都只通过 {@code onError} 汇报，不会抛出，
     * 也不会影响刷取逻辑。
     */
    public static void send(Target target, String message, Consumer<String> onError) {
        dispatch(target, message, null, onError);
    }

    /**
     * 测试连接：先调 {@code get_login_info} 验证地址与 token，成功后再往配置的目标发一条 ping 消息。
     * 两步都会把结果回报给调用方，便于玩家定位是地址错、鉴权失败还是目标 ID 不对。
     *
     * @param onInfo  进度与成功信息
     * @param onError 失败信息
     */
    public static void test(Target target, Consumer<String> onInfo, Consumer<String> onError) {
        String endpoint = normalizeUrl(target.baseUrl(), onError);
        if (endpoint == null) return;
        Long id = parseTargetId(target.targetId(), onError);
        if (id == null) return;

        onInfo.accept("正在测试 OneBot 连接…");

        MeteorExecutor.execute(() -> {
            // 第一步：验证服务可达且 token 有效
            Result login = request(endpoint + "/get_login_info", target.token(), null);
            if (!login.ok()) {
                onError.accept("OneBot 连接测试失败：" + login.describe());
                return;
            }

            onInfo.accept("OneBot 服务连接成功" + describeAccount(login.payload()));

            // 第二步：往配置的目标实际发一条 ping，确认目标 ID 与发送权限都没问题
            Result ping = request(endpoint + "/send_msg", target.token(),
                buildBody(target, id, "【村民刷附魔】ping —— 这是一条连接测试消息"));
            if (ping.ok()) {
                onInfo.accept("测试消息已发送到" + describeTarget(target) + "，请检查是否收到");
            } else {
                onError.accept("测试消息发送失败：" + ping.describe());
            }
        });
    }

    private static void dispatch(Target target, String message, Consumer<String> onInfo, Consumer<String> onError) {
        String endpoint = normalizeUrl(target.baseUrl(), onError);
        if (endpoint == null) return;
        Long id = parseTargetId(target.targetId(), onError);
        if (id == null) return;

        Map<String, Object> body = buildBody(target, id, message);
        String url = endpoint + "/send_msg";
        String token = target.token();

        MeteorExecutor.execute(() -> {
            Result result = request(url, token, body);
            if (result.ok()) {
                if (onInfo != null) onInfo.accept("OneBot 推送成功");
            } else {
                onError.accept("OneBot 推送失败：" + result.describe());
            }
        });
    }

    /** 发起请求并归一化结果。{@code body} 为 null 时发空 POST。 */
    private static Result request(String url, String token, Map<String, Object> body) {
        // exceptionHandler 会吞掉异常并返回 FailedHttpResponse（状态码 400、body 为 null），
        // 这里用它把网络层异常单独记下来，以便和 HTTP 错误区分开
        Exception[] thrown = new Exception[1];

        Http.Request request = Http.post(url).exceptionHandler(e -> thrown[0] = e);
        if (body != null) request.bodyJson(body);
        if (token != null && !token.isBlank()) request.bearer(token.trim());

        HttpResponse<String> response;
        try {
            response = request.sendStringResponse();
        } catch (Throwable t) {
            return new Result(false, "请求异常 " + t.getClass().getSimpleName(), null);
        }

        if (thrown[0] != null) {
            return new Result(false, "无法连接到服务（" + thrown[0].getClass().getSimpleName() + "），请检查地址与端口", null);
        }
        if (response == null) {
            return new Result(false, "没有收到响应", null);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return new Result(false, "鉴权失败（HTTP " + status + "），请检查 access token", null);
        }
        if (status != 200) {
            return new Result(false, "服务返回 HTTP " + status, null);
        }

        // OneBot 即使在业务失败时也会返回 200，真正的结果在 retcode 里
        JsonObject json = null;
        try {
            String raw = response.body();
            if (raw != null && !raw.isBlank()) {
                json = JsonParser.parseString(raw).getAsJsonObject();
            }
        } catch (Exception e) {
            return new Result(false, "响应不是合法的 JSON，可能不是 OneBot v11 服务", null);
        }

        if (json == null) {
            return new Result(false, "响应内容为空", null);
        }

        int retcode = -1;
        if (json.has("retcode") && !json.get("retcode").isJsonNull()) {
            try {
                retcode = json.get("retcode").getAsInt();
            } catch (Exception ignored) {
                // 保持 -1，走下面的失败分支
            }
        }

        if (retcode != 0) {
            String state = readString(json, "status");
            if (state.isEmpty()) state = "unknown";

            String reason = readString(json, "wording");
            if (reason.isEmpty()) reason = readString(json, "message");

            return new Result(false, "服务返回 retcode " + retcode + "（" + state + "）"
                + (reason.isEmpty() ? "" : "：" + reason), null);
        }

        return new Result(true, "", json);
    }

    private static Map<String, Object> buildBody(Target target, long id, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message_type", target.type() == MessageType.Group ? "group" : "private");
        body.put(target.type() == MessageType.Group ? "group_id" : "user_id", id);
        body.put("message", message);
        // 纯文本发送，避免消息里的字符被当作 CQ 码解析
        body.put("auto_escape", true);
        return body;
    }

    /** 校验并去掉末尾斜杠，失败时回报原因并返回 null。 */
    private static String normalizeUrl(String baseUrl, Consumer<String> onError) {
        if (baseUrl == null || baseUrl.isBlank()) {
            onError.accept("OneBot 地址为空");
            return null;
        }

        String endpoint = baseUrl.trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            onError.accept("OneBot 地址必须以 http:// 或 https:// 开头");
            return null;
        }
        return endpoint;
    }

    private static Long parseTargetId(String targetId, Consumer<String> onError) {
        if (targetId == null || targetId.isBlank()) {
            onError.accept("OneBot 目标 ID 为空");
            return null;
        }
        try {
            return Long.parseLong(targetId.trim());
        } catch (NumberFormatException e) {
            onError.accept("OneBot 目标 ID 必须是纯数字");
            return null;
        }
    }

    /** 从 get_login_info 的响应里取出机器人账号信息，取不到就返回空串。 */
    private static String describeAccount(JsonObject payload) {
        if (payload == null || !payload.has("data") || !payload.get("data").isJsonObject()) return "";

        JsonObject data = payload.getAsJsonObject("data");
        String nickname = readString(data, "nickname");
        String userId = readString(data, "user_id");
        if (nickname.isEmpty() && userId.isEmpty()) return "";

        return "，登录账号 " + nickname + (userId.isEmpty() ? "" : "(" + userId + ")");
    }

    private static String readString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String describeTarget(Target target) {
        return (target.type() == MessageType.Group ? "群 " : "QQ ") + target.targetId().trim();
    }

    /** 请求结果。{@code payload} 仅在成功时可能非空。失败说明里绝不包含 token。 */
    private record Result(boolean ok, String describe, JsonObject payload) {}
}
