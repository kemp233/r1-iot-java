package huan.diy.r1iot.service.hass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import huan.diy.r1iot.model.Device;
import huan.diy.r1iot.model.IotAiResp;
import huan.diy.r1iot.util.R1IotUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class HassServiceImpl {

    private static final Set<String> WHITE_LIST_PREFIX = Set.of("sensor", "automation", "switch", "light", "climate");

    private static final ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    private RestTemplate restTemplateTemp;

    private static final ObjectMapper objectMapper = R1IotUtils.getObjectMapper();

    private static RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        restTemplate = this.restTemplateTemp;
    }

    private static final LoadingCache<String, JsonNode> HASS_CACHE = CacheBuilder.newBuilder()
            .refreshAfterWrite(10, TimeUnit.MINUTES) // 每次访问，若数据超过10分钟则异步刷新
            .build(new CacheLoader<>() {
                @Override
                public JsonNode load(String deviceId) {
                    return fetchFromApi(deviceId);
                }

                @Override
                public ListenableFuture<JsonNode> reload(String deviceId, JsonNode oldValue) {
                    return Futures.submit(() -> fetchFromApi(deviceId), refreshExecutor);
                }
            });

    private static JsonNode fetchFromApi(String deviceId) {
        Device device = R1IotUtils.getDeviceMap().get(deviceId);
        Device.HASSConfig hassConfig = device.getHassConfig();
        String url = hassConfig.getEndpoint();
        url = (url.endsWith("/") ? url : (url + "/")) + "api/states";
        String token = hassConfig.getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        HttpEntity<JsonNode> entity = new HttpEntity<>(headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                JsonNode.class
        );
        JsonNode node = response.getBody();
        return filterEntities(node);

    }

    private static JsonNode filterEntities(JsonNode node) {
        ArrayNode filteredEntities = objectMapper.createArrayNode();  // 创建一个数组节点，用来存储过滤后的实体

        for (JsonNode entity : node) {
            // 获取 entity_id 前缀部分
            String entityPrefix = entity.get("entity_id").textValue().split("\\.")[0];

            // 获取友好名称
            if(Optional.ofNullable(entity.get("attributes")).map(a->a.get("friendly_name")).isEmpty()){
                continue;
            }
            String friendlyName = entity.get("attributes").get("friendly_name").textValue();

            if (StringUtils.hasLength(friendlyName) && WHITE_LIST_PREFIX.contains(entityPrefix)) {
                ObjectNode filteredEntity = objectMapper.createObjectNode();

                // 假设我们需要返回 "entity_id" 和 "name"
                filteredEntity.put("entity_id", entity.get("entity_id").textValue());
                filteredEntity.put("name", friendlyName);  // 设置为 friendly_name 或根据需要修改
                // 将过滤后的实体添加到结果数组
                filteredEntities.add(filteredEntity);
            }
        }

        return filteredEntities;  // 返回过滤后的实体列表
    }

    public String controlHass(String target, String parameter, String actValue, Device device) {
        try {
            String deviceId = device.getId();
            JsonNode entitiesNode = HASS_CACHE.get(deviceId);

            if (entitiesNode == null || entitiesNode.isEmpty()) {
                log.warn("HASS cache is empty or null for deviceId: {}", deviceId);
                return "未能连接到家庭助手，请检查网络或配置";
            }

            String entityId = findHassEntity(target, entitiesNode);
            if (entityId == null) {
                log.warn("未在 HA 中找到与 target='{}' 最匹配的实体", target);
                return "抱歉，我没找到名为‘" + target + "’的灯或开关，请确认设备名称是否正确";
            }

            IotAiResp aiIot = new IotAiResp(entityId, actValue, parameter);

            String ttsContent = "SUCCESS";

            String action = aiIot.getAction().trim().toLowerCase();
            switch (action) {
                case "on":
                    switchOperation(deviceId, aiIot.getEntityId(), true);
                    break;

                case "off":
                    switchOperation(deviceId, aiIot.getEntityId(), false);
                    break;
                case "query":
                    ttsContent = queryStatus(deviceId, aiIot.getEntityId());
                    break;
                case "set":
                    // todo
                default:
                    log.warn("未知的 action: {}", action);
                    ttsContent = "不支持的操作：" + action;
                    break;
            }

            return ttsContent;
        } catch (Exception e) {
            log.error("控制 HA 时发生异常: target={}, parameter={}, actValue={}", target, parameter, actValue, e);
            return "操作失败：" + e.getMessage();
        }

    }

    private String findHassEntity(String target, JsonNode jsonNode) {

        if (jsonNode == null || jsonNode.isEmpty()) {
            log.warn("传入的 jsonNode 为空或无数据，target={}", target);
            return null;
        }

        JsonNode mostSimilarChannel = null;
        int minDistance = Integer.MAX_VALUE;

        LevenshteinDistance levenshtein = new LevenshteinDistance();

        for (JsonNode entity : jsonNode) {
            String tvgName = entity.get("name").asText();

            // 计算编辑距离（越小越相似）
            int distance = levenshtein.apply(target, tvgName);

            if (distance < minDistance) {
                minDistance = distance;
                mostSimilarChannel = entity;
            }
        }

        if (mostSimilarChannel == null) {
            log.warn("在所有实体中未找到与 target='{}' 最相似的匹配项", target);
            return null;
        }

        String entityId = mostSimilarChannel.get("entity_id").asText();
        log.info("根据 target='{}' 匹配到实体: entity_id={}, name={}", target, entityId, mostSimilarChannel.get("name").asText());
        return entityId;
    }

    private void switchOperation(String deviceId, String entityId, boolean on) {
        new Thread(() -> {

            // 状态查询：仅用于 light 类型避免重复操作
            if (entityId.startsWith("light")) {
                try {
                    JsonNode resp = stateQuery(deviceId, entityId);
                    String val = resp.get("state").textValue();
                    if (val.equals("on") && on) {
                        log.info("灯 {} 已经是开状态，无需操作", entityId);
                        return;
                    }
                    if (val.equals("off") && !on) {
                        log.info("灯 {} 已经是关状态，无需操作", entityId);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("查询灯状态失败，仍尝试执行操作: {}", entityId, e);
                }
            }

            String url = R1IotUtils.getDeviceMap().get(deviceId).getHassConfig().getEndpoint();
            url = url.endsWith("/") ? url : (url + "/");
            String operationUrl = buildOperationUrl(entityId, url, on);

            if (operationUrl == null) {
                log.error("无法为 entityId={} 构造操作 URL，前缀不被支持: switch 或 light", entityId);
                return; // 提前退出，不发送无效请求
            }

            log.info("action url {}", operationUrl);
            log.info("action entityId {}", entityId);

            Map<String, String> entityMap = Map.of("entity_id", entityId);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + R1IotUtils.getDeviceMap().get(deviceId).getHassConfig().getToken());
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(entityMap, headers);
            ResponseEntity<String> exchange = restTemplate.exchange(
                    operationUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            log.info("iot 执行HTTP 返回码：{}", exchange.getStatusCode().toString());
            if (!exchange.getStatusCode().is2xxSuccessful()) {
                log.warn("HA 返回非成功状态码: {}, 响应体: {}", exchange.getStatusCode(), exchange.getBody());
            }

        }).start();
    }

    /**
     * 构造操作 URL：支持 turn_on / turn_off，明确处理未知类型
     */
    private String buildOperationUrl(String entityId, String baseUrl, boolean on) {
        String action = on ? "turn_on" : "turn_off";
        if (entityId.startsWith("switch")) {
            return baseUrl + "api/services/switch/" + action;
        } else if (entityId.startsWith("light")) {
            return baseUrl + "api/services/light/" + action;  // ✅ 修复：使用 turn_on / turn_off，不再是 toggle
        }
        // 对于其他类型（如 automation, script, input_boolean 等），不支持直接控制
        log.warn("entityId={} 以 {} 开头，当前仅支持 switch 和 light 类型的直接控制", 
                 entityId, 
                 entityId.split("\\.")[0]);
        return null;  // 明确返回 null，避免误发送
    }

    private String queryStatus(String deviceId, String entityId) {
        JsonNode resp = stateQuery(deviceId, entityId);
        String name = resp.get("attributes").get("friendly_name").textValue();
        String val = resp.get("state").textValue();
        return name + "是" + val;
    }

    private JsonNode stateQuery(String deviceId, String entityId) {
        String url = R1IotUtils.getDeviceMap().get(deviceId).getHassConfig().getEndpoint();
        url = url.endsWith("/") ? url : (url + "/") + "api/states/" + entityId;
        log.info("[hass] query: {}", url);

        Device device = R1IotUtils.getDeviceMap().get(deviceId);
        Device.HASSConfig hassConfig = device.getHassConfig();
        String token = hassConfig.getToken();
        log.info("[hass] token: {}", token.trim());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        HttpEntity<JsonNode> entity = new HttpEntity<>(headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                JsonNode.class
        );
        return response.getBody();
    }

}
