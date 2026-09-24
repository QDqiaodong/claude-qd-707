package com.marina.berth.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 码头词典与业务规则参数表（船型、泊位/船只/靠泊/申报状态、服务类型与默认单价、载员上限、
 * 保险预警天数等）。规则集中在此，便于出题时被要求调整或扩展。
 */
public final class MarinaDict {

    private MarinaDict() {
    }

    // ---------------- 船型 ----------------
    private static final Map<String, String> VESSEL_TYPE = Map.of(
            "SAIL", "帆船",
            "POWER", "动力艇",
            "FISHING", "钓鱼艇",
            "CATAMARAN", "双体船",
            "SPEED", "快艇");

    /** 船型排序（用于前端下拉顺序） */
    public static final List<String> VESSEL_TYPE_ORDER = List.of("SAIL", "POWER", "FISHING", "CATAMARAN", "SPEED");

    // ---------------- 船只状态 ----------------
    private static final Map<String, String> VESSEL_STATUS = Map.of(
            "IN_PORT", "在港",
            "OUT_SEA", "出海",
            "MAINTENANCE", "维修",
            "TERMINATED", "退租");

    // ---------------- 泊位状态 ----------------
    private static final Map<String, String> BERTH_STATUS = Map.of(
            "FREE", "空闲",
            "IN_PORT", "在泊",
            "RESERVED", "预留",
            "DISABLED", "停用维修");

    /** 新建泊位时允许给的状态：在泊只能靠「安排靠泊」产生 */
    public static final List<String> BERTH_STATUS_NEW = List.of("FREE", "RESERVED", "DISABLED");

    // ---------------- 靠泊记录状态 ----------------
    private static final Map<String, String> BERTHING_STATUS = Map.of(
            "IN_PORT", "在泊",
            "SETTLED", "已结算离泊");

    // ---------------- 出航申报状态 ----------------
    private static final Map<String, String> PLAN_STATUS = Map.of(
            "PENDING", "待审批",
            "APPROVED", "已批准",
            "RETURNED", "已返航",
            "CANCELED", "已取消");

    // ---------------- 码头服务类型 ----------------
    private static final Map<String, String> SERVICE_TYPE = Map.of(
            "POWER", "接电",
            "WATER", "加水",
            "FUEL", "加油",
            "HULL_WASH", "船体清洗",
            "LIFT", "吊机上下排",
            "REPAIR", "维修",
            "WASTE", "污油水接收");

    /** 服务类型默认单价（元 / 单位） */
    private static final Map<String, BigDecimal> SERVICE_PRICE = Map.of(
            "POWER", BigDecimal.valueOf(12.00),
            "WATER", BigDecimal.valueOf(15.00),
            "FUEL", BigDecimal.valueOf(8.60),
            "HULL_WASH", BigDecimal.valueOf(45.00),
            "LIFT", BigDecimal.valueOf(680.00),
            "REPAIR", BigDecimal.valueOf(260.00),
            "WASTE", BigDecimal.valueOf(120.00));

    /** 服务计费单位 */
    private static final Map<String, String> SERVICE_UNIT = Map.of(
            "POWER", "度",
            "WATER", "吨",
            "FUEL", "升",
            "HULL_WASH", "米",
            "LIFT", "次",
            "REPAIR", "工时",
            "WASTE", "次");

    /** 服务类型顺序 */
    public static final List<String> SERVICE_TYPE_ORDER =
            List.of("POWER", "WATER", "FUEL", "HULL_WASH", "LIFT", "REPAIR", "WASTE");

    // ---------------- 结算状态 ----------------
    private static final Map<String, String> SETTLE_STATUS = Map.of(
            "UNSETTLED", "未结",
            "SETTLED", "已结",
            "ON_ACCOUNT", "挂账");

    // ---------------- 水电接口 ----------------
    private static final Map<String, String> UTILITY = Map.of(
            "POWER_WATER", "水电齐全",
            "POWER", "仅供电",
            "WATER", "仅供水",
            "NONE", "无");

    /** 码头区域 */
    public static final List<String> ZONE_ORDER = List.of("A", "B", "C");

    // ---------------- 业务规则参数 ----------------
    /** 单船出航载员上限 */
    public static final int MAX_CREW = 12;

    /** 保险到期预警天数 */
    public static final int INSURANCE_WARN_DAYS = 30;

    /** 靠泊天数不足一天按一天计 */
    public static final int MIN_BERTH_DAYS = 1;

    /** 联系电话格式：11 位手机号 */
    public static final String PHONE_PATTERN = "^1[3-9]\\d{9}$";

    // ---------------- 取值方法 ----------------
    public static boolean isValidVesselType(String type) {
        return type != null && VESSEL_TYPE.containsKey(type);
    }

    public static String vesselTypeName(String type) {
        return type == null ? "" : VESSEL_TYPE.getOrDefault(type, type);
    }

    public static boolean isValidVesselStatus(String status) {
        return status != null && VESSEL_STATUS.containsKey(status);
    }

    public static String vesselStatusName(String status) {
        return status == null ? "" : VESSEL_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidBerthStatus(String status) {
        return status != null && BERTH_STATUS.containsKey(status);
    }

    public static String berthStatusName(String status) {
        return status == null ? "" : BERTH_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidBerthNewStatus(String status) {
        return status != null && BERTH_STATUS_NEW.contains(status);
    }

    public static boolean isValidBerthingStatus(String status) {
        return status != null && BERTHING_STATUS.containsKey(status);
    }

    public static String berthingStatusName(String status) {
        return status == null ? "" : BERTHING_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidPlanStatus(String status) {
        return status != null && PLAN_STATUS.containsKey(status);
    }

    public static String planStatusName(String status) {
        return status == null ? "" : PLAN_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidServiceType(String type) {
        return type != null && SERVICE_TYPE.containsKey(type);
    }

    public static String serviceTypeName(String type) {
        return type == null ? "" : SERVICE_TYPE.getOrDefault(type, type);
    }

    public static String serviceUnit(String type) {
        return SERVICE_UNIT.getOrDefault(type, "次");
    }

    public static BigDecimal servicePrice(String type) {
        return SERVICE_PRICE.getOrDefault(type, BigDecimal.ZERO);
    }

    public static boolean isValidSettleStatus(String status) {
        return status != null && SETTLE_STATUS.containsKey(status);
    }

    public static String settleStatusName(String status) {
        return status == null ? "" : SETTLE_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidUtility(String utility) {
        return utility != null && UTILITY.containsKey(utility);
    }

    public static String utilityName(String utility) {
        return utility == null ? "" : UTILITY.getOrDefault(utility, utility);
    }

    public static boolean isValidZone(String zone) {
        return zone != null && ZONE_ORDER.contains(zone);
    }

    /** 允许船型串（分号分隔）里是否包含该船型 */
    public static boolean allowTypeContains(String allowType, String vesselType) {
        if (allowType == null || vesselType == null) {
            return false;
        }
        for (String part : allowType.split(";")) {
            if (vesselType.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 允许船型串转成中文名，如 SAIL;POWER → 帆船/动力艇 */
    public static String allowTypeNames(String allowType) {
        if (allowType == null || allowType.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : allowType.split(";")) {
            String code = part.trim();
            if (code.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(vesselTypeName(code));
        }
        return sb.toString();
    }
}
