-- ============================================================================
-- 游艇码头 Marina 运营管理 · 建表 + 种子数据
-- 库名 marina_berth，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
-- 后端 spring.jpa.hibernate.ddl-auto=none，表结构与演示数据全靠这份脚本。
--
-- 五个业务模块：
--   1. berth           泊位（区域 A/B/C + 栈桥、泊位长度、最大吃水、允许船型、日租金）
--   2. vessel          船只（船名/船籍号/船主/船型/主尺度/登记日期/保险到期日）
--   3. berthing        靠泊记录（靠泊 → 离泊结算，天数 × 日租金 = 停泊费）
--   4. sailing_plan    出航申报（待审批 → 已批准 → 已返航 / 已取消）
--   5. service_record  码头服务与费用（接电/加水/加油/清洗/吊机/维修/污油水）
--
-- 数据访问主路线：berth 上没有「当前船只」字段、vessel 上没有「当前泊位」字段，
-- 这两个派生关联由实体上的 Hibernate @JoinFormula 从 berthing 表的「在泊」行现算，
-- 所以下面 berthing 里的 IN_PORT 行必须与 berth.status = 'IN_PORT' 一一对应。
--
-- 种子数据对照：
--   [M1] 泊位状态齐活      → IN_PORT 9 / RESERVED 2 / DISABLED 2 / FREE 11（共 24 个）
--   [M1] @JoinFormula      → 9 条在泊记录让 A-01/A-02/A-05/B-01/B-03/B-04/B-08/C-02/C-05 带出船名
--   [M2] 保险预警          → 双子星已逾期 5 天、碧海钓翁剩 12 天、海之韵剩 25 天
--   [M3] 停泊费 = 天数×日租金 → SETTLED 的 13 条都已按泊位日租金算好
--   [M4] 超期未返          → SP-2026-0006 超 6 小时、SP-2026-0007 超 28 小时
--   [M5] 月度费用汇总      → 服务记录横跨近 45 天，可落进两个自然月做月度对比
-- ============================================================================

-- 脚本里的中文是 UTF-8：先声明客户端字符集，否则 mysql 客户端按 latin1 读文件会存成乱码
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS marina_berth DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE marina_berth;

-- ---------------------------- 模块一：泊位 ----------------------------
CREATE TABLE berth (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  berth_no     VARCHAR(16)  NOT NULL COMMENT '泊位号，如 A-01',
  zone_code    VARCHAR(4)   NOT NULL COMMENT '所属区域 A/B/C',
  pier_no      VARCHAR(16)  NOT NULL COMMENT '栈桥编号，如 A1',
  length_m     DECIMAL(6,2) NOT NULL COMMENT '泊位长度（m）',
  max_draft_m  DECIMAL(5,2) NOT NULL COMMENT '最大吃水（m）',
  allow_type   VARCHAR(64)  NOT NULL COMMENT '允许船型，分号分隔 SAIL;POWER;FISHING;CATAMARAN;SPEED',
  daily_rate   DECIMAL(10,2) NOT NULL COMMENT '日租金（元/天）',
  utility      VARCHAR(16)  NOT NULL COMMENT '水电接口 POWER_WATER/POWER/WATER/NONE',
  status       VARCHAR(16)  NOT NULL COMMENT 'FREE 空闲 / IN_PORT 在泊 / RESERVED 预留 / DISABLED 停用维修',
  updated_at   DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_berth_no (berth_no),
  KEY idx_berth_zone (zone_code),
  KEY idx_berth_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='泊位';

-- ---------------------------- 模块二：船只 ----------------------------
CREATE TABLE vessel (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  name          VARCHAR(32)  NOT NULL COMMENT '船名',
  reg_no        VARCHAR(24)  NOT NULL COMMENT '船籍注册号',
  owner_name    VARCHAR(32)  NOT NULL COMMENT '船主',
  type_code     VARCHAR(16)  NOT NULL COMMENT 'SAIL 帆船/POWER 动力艇/FISHING 钓鱼艇/CATAMARAN 双体船/SPEED 快艇',
  length_m      DECIMAL(6,2) NOT NULL COMMENT '船长（m）',
  width_m       DECIMAL(5,2) NOT NULL COMMENT '船宽（m）',
  draft_m       DECIMAL(5,2) NOT NULL COMMENT '吃水（m）',
  reg_date      DATE         NOT NULL COMMENT '登记日期',
  insurance_due DATE         NOT NULL COMMENT '保险到期日',
  status        VARCHAR(16)  NOT NULL COMMENT 'IN_PORT 在港/OUT_SEA 出海/MAINTENANCE 维修/TERMINATED 退租',
  updated_at    DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_vessel_reg_no (reg_no),
  UNIQUE KEY uk_vessel_name (name),
  KEY idx_vessel_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='船只台账';

-- ---------------------------- 模块三：靠泊记录 ----------------------------
CREATE TABLE berthing (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  record_no     VARCHAR(24)  NOT NULL COMMENT '记录单号',
  berth_id      BIGINT       NOT NULL,
  vessel_id     BIGINT       NOT NULL,
  berth_time    DATETIME     NOT NULL COMMENT '靠泊时间',
  eta_depart    DATETIME     NULL     COMMENT '预计离泊时间',
  actual_depart DATETIME     NULL     COMMENT '实际离泊时间',
  days          INT          NULL     COMMENT '停泊天数（不足一天按一天）',
  fee           DECIMAL(12,2) NULL    COMMENT '停泊费用 = 天数 × 日租金',
  status        VARCHAR(16)  NOT NULL COMMENT 'IN_PORT 在泊 / SETTLED 已结算离泊',
  updated_at    DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_berthing_no (record_no),
  KEY idx_berthing_berth (berth_id),
  KEY idx_berthing_vessel (vessel_id),
  KEY idx_berthing_status (status),
  CONSTRAINT fk_berthing_berth FOREIGN KEY (berth_id) REFERENCES berth (id),
  CONSTRAINT fk_berthing_vessel FOREIGN KEY (vessel_id) REFERENCES vessel (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='靠泊记录';

-- ---------------------------- 模块四：出航申报 ----------------------------
CREATE TABLE sailing_plan (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  plan_no     VARCHAR(24)  NOT NULL COMMENT '申报单号',
  vessel_id   BIGINT       NOT NULL,
  skipper     VARCHAR(32)  NOT NULL COMMENT '船长 / 驾驶员',
  phone       VARCHAR(20)  NOT NULL COMMENT '联系电话',
  depart_time DATETIME     NOT NULL COMMENT '出航时间',
  eta_return  DATETIME     NOT NULL COMMENT '预计返回时间',
  route       VARCHAR(128) NOT NULL COMMENT '目的地 / 航线',
  crew_count  INT          NOT NULL COMMENT '载员人数',
  weather     VARCHAR(128) NOT NULL COMMENT '海况天气预报',
  status      VARCHAR(16)  NOT NULL COMMENT 'PENDING 待审批/APPROVED 已批准/SUSPENDED 暂停放行/RETURNED 已返航/CANCELED 已取消',
  -- 封航生效时被「暂停放行」的申报挂在这三列；解除归档后 status 回 PENDING，
  -- 但 hold_seq / hold_reason 保留不擦，作为「原审批顺序 + 暂停原因」的可追溯痕迹
  hold_by_order_id BIGINT  NULL     COMMENT '暂停它的封航令 id（解除后置空）',
  hold_seq    INT          NULL     COMMENT '暂停时的原审批顺序（按出航时间排），解除后按此顺序恢复',
  hold_reason VARCHAR(256) NULL     COMMENT '暂停原因（封航令编号 + 事由），解除后保留留痕',
  updated_at  DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plan_no (plan_no),
  KEY idx_plan_vessel (vessel_id),
  KEY idx_plan_status (status),
  KEY idx_plan_hold (hold_by_order_id),
  CONSTRAINT fk_plan_vessel FOREIGN KEY (vessel_id) REFERENCES vessel (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出航申报';

-- ---------------------------- 模块五：码头服务与费用 ----------------------------
CREATE TABLE service_record (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  service_type  VARCHAR(16)  NOT NULL COMMENT 'POWER 接电/WATER 加水/FUEL 加油/HULL_WASH 船体清洗/LIFT 吊机上下排/REPAIR 维修/WASTE 污油水接收',
  unit_price    DECIMAL(10,2) NOT NULL COMMENT '单价',
  quantity      DECIMAL(10,2) NOT NULL COMMENT '数量',
  amount        DECIMAL(12,2) NOT NULL COMMENT '金额 = 单价 × 数量',
  occurred_date DATE         NOT NULL COMMENT '发生日期',
  berth_id      BIGINT       NULL     COMMENT '关联泊位（可空）',
  vessel_id     BIGINT       NULL     COMMENT '关联船只（可空）',
  settle_status VARCHAR(16)  NOT NULL COMMENT 'UNSETTLED 未结/SETTLED 已结/ON_ACCOUNT 挂账',
  updated_at    DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_service_type (service_type),
  KEY idx_service_settle (settle_status),
  KEY idx_service_berth (berth_id),
  KEY idx_service_vessel (vessel_id),
  CONSTRAINT fk_service_berth FOREIGN KEY (berth_id) REFERENCES berth (id),
  CONSTRAINT fk_service_vessel FOREIGN KEY (vessel_id) REFERENCES vessel (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='码头服务与费用';

-- ============================================================================
-- 种子数据：泊位 24 个（A/B/C 区各 8 个，A 区小船位、B 区中泊位、C 区大泊位）
-- id 1~8 = A-01~A-08，9~16 = B-01~B-08，17~24 = C-01~C-08
-- ============================================================================
INSERT INTO berth (id, berth_no, zone_code, pier_no, length_m, max_draft_m, allow_type, daily_rate, utility, status, updated_at) VALUES
 (1,  'A-01', 'A', 'A1', 10.50, 1.80, 'SAIL;FISHING;SPEED',          280.00, 'POWER_WATER', 'IN_PORT',  NOW()),
 (2,  'A-02', 'A', 'A1',  9.80, 1.60, 'SAIL;SPEED',                  260.00, 'POWER',       'IN_PORT',  NOW()),
 (3,  'A-03', 'A', 'A1', 11.00, 2.00, 'SAIL;FISHING;SPEED',          300.00, 'POWER_WATER', 'FREE',     NOW()),
 (4,  'A-04', 'A', 'A1', 12.00, 2.20, 'SAIL;FISHING;SPEED;POWER',    320.00, 'POWER_WATER', 'RESERVED', NOW()),
 (5,  'A-05', 'A', 'A2', 12.00, 2.00, 'FISHING;SPEED',               300.00, 'POWER_WATER', 'IN_PORT',  NOW()),
 (6,  'A-06', 'A', 'A2', 10.00, 1.80, 'SAIL;SPEED',                  270.00, 'WATER',       'FREE',     NOW()),
 (7,  'A-07', 'A', 'A2', 11.50, 2.10, 'SAIL;FISHING;SPEED',          290.00, 'POWER_WATER', 'DISABLED', NOW()),
 (8,  'A-08', 'A', 'A2', 12.50, 2.30, 'SAIL;FISHING;SPEED;POWER',    330.00, 'POWER_WATER', 'FREE',     NOW()),
 (9,  'B-01', 'B', 'B1', 16.00, 2.60, 'POWER;CATAMARAN;FISHING',     480.00, 'POWER_WATER', 'IN_PORT',  NOW()),
 (10, 'B-02', 'B', 'B1', 15.00, 2.40, 'POWER;CATAMARAN',             450.00, 'POWER_WATER', 'FREE',     NOW()),
 (11, 'B-03', 'B', 'B1', 15.50, 2.20, 'CATAMARAN;POWER',             460.00, 'POWER_WATER', 'IN_PORT',  NOW()),
 (12, 'B-04', 'B', 'B1', 15.80, 2.30, 'CATAMARAN;POWER',             470.00, 'POWER',       'IN_PORT',  NOW()),
 (13, 'B-05', 'B', 'B2', 17.00, 2.80, 'POWER;CATAMARAN;FISHING',     520.00, 'POWER_WATER', 'RESERVED', NOW()),
 (14, 'B-06', 'B', 'B2', 14.50, 2.30, 'POWER;FISHING',               430.00, 'WATER',       'FREE',     NOW()),
 (15, 'B-07', 'B', 'B2', 18.00, 3.00, 'POWER;CATAMARAN;FISHING',     560.00, 'POWER_WATER', 'FREE',     NOW()),
 (16, 'B-08', 'B', 'B2', 17.50, 2.90, 'POWER;CATAMARAN',             540.00, 'POWER_WATER', 'IN_PORT',  NOW()),
 (17, 'C-01', 'C', 'C1', 20.00, 3.20, 'POWER;CATAMARAN',             720.00, 'POWER_WATER', 'FREE',     NOW()),
 (18, 'C-02', 'C', 'C1', 22.00, 3.40, 'POWER;CATAMARAN',             780.00, 'POWER_WATER', 'IN_PORT',  NOW()),
 (19, 'C-03', 'C', 'C1', 24.00, 3.60, 'POWER;CATAMARAN',             860.00, 'POWER_WATER', 'DISABLED', NOW()),
 (20, 'C-04', 'C', 'C1', 19.00, 3.00, 'POWER;CATAMARAN',             680.00, 'POWER',       'FREE',     NOW()),
 (21, 'C-05', 'C', 'C2', 18.50, 2.80, 'POWER;CATAMARAN;SPEED',       660.00, 'POWER_WATER', 'IN_PORT',  NOW()),
 (22, 'C-06', 'C', 'C2', 21.00, 3.30, 'POWER;CATAMARAN',             740.00, 'POWER_WATER', 'FREE',     NOW()),
 (23, 'C-07', 'C', 'C2', 25.00, 3.80, 'POWER;CATAMARAN',             920.00, 'POWER_WATER', 'FREE',     NOW()),
 (24, 'C-08', 'C', 'C2', 23.00, 3.50, 'POWER;CATAMARAN',             800.00, 'POWER_WATER', 'FREE',     NOW());

-- ============================================================================
-- 种子数据：船只 15 艘（在港 9 / 出海 4 / 维修 1 / 退租 1）
-- ============================================================================
INSERT INTO vessel (id, name, reg_no, owner_name, type_code, length_m, width_m, draft_m, reg_date, insurance_due, status, updated_at) VALUES
 (1,  '海风号',   'CN-YC-1001', '陈志远', 'SAIL',       9.80,  3.20, 1.50, '2021-04-18', DATE_ADD(CURDATE(), INTERVAL 240 DAY), 'IN_PORT',     NOW()),
 (2,  '逐浪者',   'CN-YC-1002', '林浩',   'SPEED',      8.60,  2.80, 0.90, '2022-06-11', DATE_ADD(CURDATE(), INTERVAL 180 DAY), 'IN_PORT',     NOW()),
 (3,  '碧海钓翁', 'CN-YC-1003', '周国平', 'FISHING',   11.20,  3.60, 1.60, '2019-09-03', DATE_ADD(CURDATE(), INTERVAL  12 DAY), 'IN_PORT',     NOW()),
 (4,  '珊瑚湾',   'CN-YC-1004', '苏婉',   'CATAMARAN', 13.80,  6.50, 1.10, '2020-05-20', DATE_ADD(CURDATE(), INTERVAL 300 DAY), 'IN_PORT',     NOW()),
 (5,  '远航者',   'CN-YC-1005', '何建明', 'POWER',     15.60,  4.60, 2.20, '2018-03-15', DATE_ADD(CURDATE(), INTERVAL 120 DAY), 'IN_PORT',     NOW()),
 (6,  '白帆',     'CN-YC-1006', '邓子谦', 'SAIL',      10.40,  3.30, 1.70, '2021-11-28', DATE_ADD(CURDATE(), INTERVAL 260 DAY), 'OUT_SEA',     NOW()),
 (7,  '疾风',     'CN-YC-1007', '郑凯',   'SPEED',      7.90,  2.60, 0.80, '2023-02-09', DATE_ADD(CURDATE(), INTERVAL 200 DAY), 'OUT_SEA',     NOW()),
 (8,  '海钓王',   'CN-YC-1008', '吴德海', 'FISHING',   10.80,  3.40, 1.40, '2020-08-17', DATE_ADD(CURDATE(), INTERVAL  90 DAY), 'OUT_SEA',     NOW()),
 (9,  '双子星',   'CN-YC-1009', '梁静怡', 'CATAMARAN', 14.20,  6.80, 1.20, '2022-01-25', DATE_SUB(CURDATE(), INTERVAL   5 DAY), 'IN_PORT',     NOW()),
 (10, '蓝鲸号',   'CN-YC-1010', '马文杰', 'POWER',     18.60,  5.20, 2.60, '2017-07-06', DATE_ADD(CURDATE(), INTERVAL 150 DAY), 'MAINTENANCE', NOW()),
 (11, '晨曦',     'CN-YC-1011', '叶知秋', 'SAIL',      11.60,  3.50, 1.80, '2023-05-19', DATE_ADD(CURDATE(), INTERVAL 330 DAY), 'OUT_SEA',     NOW()),
 (12, '追潮',     'CN-YC-1012', '高翔',   'SPEED',     12.40,  3.10, 1.00, '2021-10-02', DATE_ADD(CURDATE(), INTERVAL 210 DAY), 'IN_PORT',     NOW()),
 (13, '海之韵',   'CN-YC-1013', '罗启明', 'POWER',     16.80,  4.90, 2.50, '2019-12-12', DATE_ADD(CURDATE(), INTERVAL  25 DAY), 'IN_PORT',     NOW()),
 (14, '老船长',   'CN-YC-1014', '徐福生', 'FISHING',    9.60,  3.10, 1.30, '2016-04-08', DATE_ADD(CURDATE(), INTERVAL  60 DAY), 'TERMINATED',  NOW()),
 (15, '银河',     'CN-YC-1015', '白露',   'CATAMARAN', 16.50,  7.20, 1.50, '2021-08-30', DATE_ADD(CURDATE(), INTERVAL 170 DAY), 'IN_PORT',     NOW());

-- ============================================================================
-- 种子数据：靠泊记录 22 条（在泊 9 条 + 已结算离泊 13 条）
-- 在泊这 9 条就是 @JoinFormula 派生关联的数据来源
-- ============================================================================
INSERT INTO berthing (id, record_no, berth_id, vessel_id, berth_time, eta_depart, actual_depart, days, fee, status, updated_at) VALUES
 -- 在泊（对应 berth.status = IN_PORT）
 (1,  'KP-2026-0001',  1,  1, DATE_SUB(NOW(), INTERVAL  3 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 (2,  'KP-2026-0002',  2,  2, DATE_SUB(NOW(), INTERVAL  6 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 (3,  'KP-2026-0003',  5,  3, DATE_SUB(NOW(), INTERVAL  2 DAY), DATE_ADD(NOW(), INTERVAL 4 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 (4,  'KP-2026-0004',  9,  5, DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_ADD(NOW(), INTERVAL 3 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 (5,  'KP-2026-0005', 11,  4, DATE_SUB(NOW(), INTERVAL  5 DAY), DATE_ADD(NOW(), INTERVAL 6 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 (6,  'KP-2026-0006', 12,  9, DATE_SUB(NOW(), INTERVAL  1 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 (7,  'KP-2026-0007', 16, 13, DATE_SUB(NOW(), INTERVAL  8 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 (8,  'KP-2026-0008', 18, 15, DATE_SUB(NOW(), INTERVAL  4 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 (9,  'KP-2026-0009', 21, 12, DATE_SUB(NOW(), INTERVAL  9 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY), NULL, NULL, NULL, 'IN_PORT', NOW()),
 -- 已结算离泊（fee = days × 泊位日租金）
 (10, 'KP-2026-0010',  1,  6, DATE_SUB(NOW(), INTERVAL 40 DAY), NULL, DATE_SUB(NOW(), INTERVAL 36 DAY),  4, 1120.00, 'SETTLED', NOW()),
 (11, 'KP-2026-0011',  3,  7, DATE_SUB(NOW(), INTERVAL 30 DAY), NULL, DATE_SUB(NOW(), INTERVAL 27 DAY),  3,  900.00, 'SETTLED', NOW()),
 (12, 'KP-2026-0012',  6, 14, DATE_SUB(NOW(), INTERVAL 60 DAY), NULL, DATE_SUB(NOW(), INTERVAL 52 DAY),  8, 2160.00, 'SETTLED', NOW()),
 (13, 'KP-2026-0013',  7,  8, DATE_SUB(NOW(), INTERVAL 25 DAY), NULL, DATE_SUB(NOW(), INTERVAL 22 DAY),  3,  870.00, 'SETTLED', NOW()),
 (14, 'KP-2026-0014',  8, 11, DATE_SUB(NOW(), INTERVAL 20 DAY), NULL, DATE_SUB(NOW(), INTERVAL 14 DAY),  6, 1980.00, 'SETTLED', NOW()),
 (15, 'KP-2026-0015', 10,  5, DATE_SUB(NOW(), INTERVAL 45 DAY), NULL, DATE_SUB(NOW(), INTERVAL 38 DAY),  7, 3150.00, 'SETTLED', NOW()),
 (16, 'KP-2026-0016', 13, 13, DATE_SUB(NOW(), INTERVAL 35 DAY), NULL, DATE_SUB(NOW(), INTERVAL 30 DAY),  5, 2600.00, 'SETTLED', NOW()),
 (17, 'KP-2026-0017', 14,  9, DATE_SUB(NOW(), INTERVAL 28 DAY), NULL, DATE_SUB(NOW(), INTERVAL 24 DAY),  4, 1720.00, 'SETTLED', NOW()),
 (18, 'KP-2026-0018', 15, 10, DATE_SUB(NOW(), INTERVAL 50 DAY), NULL, DATE_SUB(NOW(), INTERVAL 44 DAY),  6, 3360.00, 'SETTLED', NOW()),
 (19, 'KP-2026-0019', 17, 15, DATE_SUB(NOW(), INTERVAL 33 DAY), NULL, DATE_SUB(NOW(), INTERVAL 28 DAY),  5, 3600.00, 'SETTLED', NOW()),
 (20, 'KP-2026-0020', 19,  5, DATE_SUB(NOW(), INTERVAL 70 DAY), NULL, DATE_SUB(NOW(), INTERVAL 65 DAY),  5, 4300.00, 'SETTLED', NOW()),
 (21, 'KP-2026-0021', 22, 12, DATE_SUB(NOW(), INTERVAL 18 DAY), NULL, DATE_SUB(NOW(), INTERVAL 15 DAY),  3, 2220.00, 'SETTLED', NOW()),
 (22, 'KP-2026-0022', 24,  4, DATE_SUB(NOW(), INTERVAL 26 DAY), NULL, DATE_SUB(NOW(), INTERVAL 21 DAY),  5, 4000.00, 'SETTLED', NOW());

-- ============================================================================
-- 种子数据：出航申报 12 条（待审批 3 / 已批准 4 其中 2 条超期未返 / 已返航 4 / 已取消 1）
-- ============================================================================
INSERT INTO sailing_plan (id, plan_no, vessel_id, skipper, phone, depart_time, eta_return, route, crew_count, weather, status, updated_at) VALUES
 (1,  'SP-2026-0001',  1, '陈志远', '13905510001', DATE_ADD(NOW(), INTERVAL  1 DAY), DATE_ADD(NOW(), INTERVAL  2 DAY), '东澳岛 — 环岛航线',        4, '东南风 3 级，浪高 0.6m，能见度良好', 'PENDING',  NOW()),
 (2,  'SP-2026-0002', 13, '罗启明', '13905510013', DATE_ADD(NOW(), INTERVAL  2 DAY), DATE_ADD(NOW(), INTERVAL  3 DAY), '万山群岛 — 北线',          6, '东北风 4 级，浪高 1.2m，午后有阵雨', 'PENDING',  NOW()),
 (3,  'SP-2026-0003',  5, '何建明', '13905510005', DATE_ADD(NOW(), INTERVAL  3 DAY), DATE_ADD(NOW(), INTERVAL  5 DAY), '外伶仃岛 — 东湾锚地',      8, '西南风 3 级，浪高 0.8m，海况平稳',   'PENDING',  NOW()),
 (4,  'SP-2026-0004',  6, '邓子谦', '13905510006', DATE_SUB(NOW(), INTERVAL  2 DAY), DATE_ADD(NOW(), INTERVAL  1 DAY), '桂山岛 — 西堤水域',        5, '东风 4 级，浪高 1.0m，夜间转静',     'APPROVED', NOW()),
 (5,  'SP-2026-0005',  7, '郑凯',   '13905510007', DATE_SUB(NOW(), INTERVAL  1 DAY), DATE_ADD(NOW(), INTERVAL  1 DAY), '庙湾 — 珊瑚礁潜点',        3, '东南风 3 级，浪高 0.7m，适合潜水',   'APPROVED', NOW()),
 (6,  'SP-2026-0006',  8, '吴德海', '13905510008', DATE_SUB(NOW(), INTERVAL 30 HOUR), DATE_SUB(NOW(), INTERVAL  6 HOUR), '担杆岛 — 外海钓场',       4, '东北风 5 级，浪高 1.6m，已发大风提示', 'APPROVED', NOW()),
 (7,  'SP-2026-0007', 11, '叶知秋', '13905510011', DATE_SUB(NOW(), INTERVAL 52 HOUR), DATE_SUB(NOW(), INTERVAL 28 HOUR), '外海训练航线 — 折返跑',   6, '南风 4 级，浪高 1.3m，能见度 6km',   'APPROVED', NOW()),
 (8,  'SP-2026-0008',  2, '林浩',   '13905510002', DATE_SUB(NOW(), INTERVAL  5 DAY), DATE_SUB(NOW(), INTERVAL  4 DAY), '近岸试航 — 5 海里折返',   2, '微风 2 级，浪高 0.3m，海况优',       'RETURNED', NOW()),
 (9,  'SP-2026-0009', 15, '白露',   '13905510015', DATE_SUB(NOW(), INTERVAL  7 DAY), DATE_SUB(NOW(), INTERVAL  6 DAY), '珠澳口岸水域 — 观光航线', 10, '东南风 3 级，浪高 0.9m，适航',       'RETURNED', NOW()),
 (10, 'SP-2026-0010',  3, '周国平', '13905510003', DATE_SUB(NOW(), INTERVAL  9 DAY), DATE_SUB(NOW(), INTERVAL  8 DAY), '青洲钓场 — 夜钓',          4, '北风 3 级，浪高 0.5m，夜间降温',     'RETURNED', NOW()),
 (11, 'SP-2026-0011', 12, '高翔',   '13905510012', DATE_SUB(NOW(), INTERVAL  3 DAY), DATE_SUB(NOW(), INTERVAL  2 DAY), '东澳岛 — 南沙湾',          3, '东北风 6 级，浪高 2.0m，不适航',     'CANCELED', NOW()),
 (12, 'SP-2026-0012',  1, '陈志远', '13905510001', DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 11 DAY), '桂山岛 — 环岛',            4, '东南风 2 级，浪高 0.4m，海况优',     'RETURNED', NOW());

-- ============================================================================
-- 种子数据：码头服务与费用 16 条（未结 5 / 已结 9 / 挂账 2，横跨近 45 天）
-- ============================================================================
INSERT INTO service_record (id, service_type, unit_price, quantity, amount, occurred_date, berth_id, vessel_id, settle_status, updated_at) VALUES
 (1,  'POWER',      12.00, 120.00, 1440.00, DATE_SUB(CURDATE(), INTERVAL  5 DAY),  1,  1, 'UNSETTLED',  NOW()),
 (2,  'WATER',      15.00,   8.00,  120.00, DATE_SUB(CURDATE(), INTERVAL  4 DAY),  1,  1, 'SETTLED',    NOW()),
 (3,  'FUEL',        8.60, 200.00, 1720.00, DATE_SUB(CURDATE(), INTERVAL  3 DAY),  9,  5, 'UNSETTLED',  NOW()),
 (4,  'HULL_WASH',  45.00,  15.60,  702.00, DATE_SUB(CURDATE(), INTERVAL  2 DAY),  9,  5, 'SETTLED',    NOW()),
 (5,  'LIFT',      680.00,   1.00,  680.00, DATE_SUB(CURDATE(), INTERVAL 12 DAY), 17, 15, 'SETTLED',    NOW()),
 (6,  'REPAIR',    260.00,   6.00, 1560.00, DATE_SUB(CURDATE(), INTERVAL 15 DAY),  7,  8, 'ON_ACCOUNT', NOW()),
 (7,  'WASTE',     120.00,   2.00,  240.00, DATE_SUB(CURDATE(), INTERVAL  8 DAY), 11,  4, 'UNSETTLED',  NOW()),
 (8,  'POWER',      12.00,  86.00, 1032.00, DATE_SUB(CURDATE(), INTERVAL 20 DAY), 16, 13, 'SETTLED',    NOW()),
 (9,  'FUEL',        8.60, 350.00, 3010.00, DATE_SUB(CURDATE(), INTERVAL 25 DAY), 18, 15, 'SETTLED',    NOW()),
 (10, 'HULL_WASH',  45.00,  22.00,  990.00, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 18, 15, 'UNSETTLED',  NOW()),
 (11, 'WATER',      15.00,  12.00,  180.00, DATE_SUB(CURDATE(), INTERVAL  6 DAY),  2,  2, 'SETTLED',    NOW()),
 (12, 'REPAIR',    260.00,   3.50,  910.00, DATE_SUB(CURDATE(), INTERVAL 18 DAY), 12,  9, 'ON_ACCOUNT', NOW()),
 (13, 'POWER',      12.00, 210.00, 2520.00, DATE_SUB(CURDATE(), INTERVAL 40 DAY), 21, 12, 'SETTLED',    NOW()),
 (14, 'LIFT',      680.00,   2.00, 1360.00, DATE_SUB(CURDATE(), INTERVAL 45 DAY), 19, 10, 'SETTLED',    NOW()),
 (15, 'WASTE',     120.00,   1.00,  120.00, DATE_SUB(CURDATE(), INTERVAL 10 DAY),  5,  3, 'UNSETTLED',  NOW()),
 (16, 'FUEL',        8.60,  90.00,  774.00, DATE_SUB(CURDATE(), INTERVAL  1 DAY),  2,  2, 'UNSETTLED',  NOW());

-- ============================================================================
-- 模块六：封航令 navigation_closure_order
--   阶段机：PENDING 待生效 → EFFECTIVE 生效中 → RELEASING 解除中 → ARCHIVED 已归档
--   约束：同一时刻最多只有一条「未归档」的封航令 —— active_guard 是生成列：
--   未归档行恒为 1，UNIQUE 索引保证第二条插不进来（并发发布也撞键）。
-- ============================================================================
CREATE TABLE navigation_closure_order (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  order_no       VARCHAR(24)  NOT NULL COMMENT '封航令编号，如 FH-202610-0001',
  reason_type    VARCHAR(16)  NOT NULL COMMENT 'TYPHOON 台风/MARITIME_CONTROL 海事管制/WATER_RISK 突发水域风险',
  title          VARCHAR(128) NOT NULL COMMENT '封航事由标题',
  description    VARCHAR(512) NOT NULL COMMENT '详细说明',
  issued_by      VARCHAR(32)  NOT NULL COMMENT '发布人（值班主管）',
  status         VARCHAR(16)  NOT NULL COMMENT 'PENDING 待生效/EFFECTIVE 生效中/RELEASING 解除中/ARCHIVED 已归档',
  effective_at   DATETIME     NOT NULL COMMENT '发布生效时间（到点才真正生效）',
  activated_at   DATETIME     NULL     COMMENT '实际生效时间（定时到点或手动激活）',
  released_at    DATETIME     NULL     COMMENT '申请解除时间（进入解除中）',
  archived_at    DATETIME     NULL     COMMENT '归档时间（风险项清零后）',
  archived_by    VARCHAR(32)  NULL     COMMENT '归档操作人',
  release_remark VARCHAR(256) NULL     COMMENT '解除备注',
  active_guard   TINYINT GENERATED ALWAYS AS (CASE WHEN status <> 'ARCHIVED' THEN 1 ELSE NULL END) STORED COMMENT '未归档=1，配合唯一索引保证同时只有一条未归档令',
  created_at     DATETIME     NOT NULL,
  updated_at     DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_closure_no (order_no),
  UNIQUE KEY uk_closure_active (active_guard),
  KEY idx_closure_status (status),
  KEY idx_closure_effective (effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='封航令';

-- ============================================================================
-- 模块六：封航令 —— 受影响出航申报台账（影响流水，不随申报当前状态改变而删除）
--   一条封航令对一张申报做了什么（暂停放行 / 新建受阻 / 解除后恢复）全在这里留痕，
--   封航令归档后仍然可查，满足「历史封航令与处置过程必须可追溯」。
-- ============================================================================
CREATE TABLE closure_plan_effect (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  closure_order_id BIGINT       NOT NULL,
  sailing_plan_id  BIGINT       NOT NULL,
  effect_type      VARCHAR(24)  NOT NULL COMMENT 'SUSPENDED 暂停放行/BLOCKED_NEW 新建待批受阻/RESTORED 解除后恢复可审批',
  plan_status_snap VARCHAR(16)  NOT NULL COMMENT '发生影响时申报状态快照',
  hold_seq         INT          NULL     COMMENT '暂停时分配的原审批顺序',
  remark           VARCHAR(256) NULL     COMMENT '说明（暂停原因等）',
  operator         VARCHAR(32)  NOT NULL,
  created_at       DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_effect_order (closure_order_id),
  KEY idx_effect_plan (sailing_plan_id),
  CONSTRAINT fk_effect_order FOREIGN KEY (closure_order_id) REFERENCES navigation_closure_order (id),
  CONSTRAINT fk_effect_plan FOREIGN KEY (sailing_plan_id) REFERENCES sailing_plan (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='封航令-受影响申报台账';

-- ============================================================================
-- 模块六：返港处置项 return_disposition
--   封航前已出海的船，封航令一生效就为其生成处置项，四阶段：
--   TO_CONTACT 待联系 → CONFIRMED_RETURN 已确认返港 / LOST_CONTACT 失联升级
--   → BERTHED 已安全靠泊（终态）。
--   同一艘船在同一条生效封航令下只能有一条：uk_disp_order_vessel 兜底，
--   两名值班员并发确认 / 重复点击 / 超时重试，第二条 INSERT 直接撞唯一键。
-- ============================================================================
CREATE TABLE return_disposition (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  closure_order_id BIGINT       NOT NULL,
  vessel_id        BIGINT       NOT NULL,
  sailing_plan_id  BIGINT       NULL     COMMENT '对应的已批准出航申报（无申报出海则为空）',
  status           VARCHAR(24)  NOT NULL COMMENT 'TO_CONTACT 待联系/CONFIRMED_RETURN 已确认返港/LOST_CONTACT 失联升级/BERTHED 已安全靠泊',
  planned_berth_id BIGINT       NULL     COMMENT '确认返港时预约的接纳泊位',
  contacted_at     DATETIME     NULL     COMMENT '首次联系上的时间',
  confirmed_at     DATETIME     NULL     COMMENT '确认返港时间',
  escalated_at     DATETIME     NULL     COMMENT '失联升级时间',
  berthed_at       DATETIME     NULL     COMMENT '安全靠泊时间',
  berth_id         BIGINT       NULL     COMMENT '实际靠泊泊位',
  berthing_id      BIGINT       NULL     COMMENT '靠泊记录 id',
  created_at       DATETIME     NOT NULL,
  updated_at       DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_disp_order_vessel (closure_order_id, vessel_id),
  KEY idx_disp_status (status),
  KEY idx_disp_vessel (vessel_id),
  KEY idx_disp_berth (berth_id),
  CONSTRAINT fk_disp_order FOREIGN KEY (closure_order_id) REFERENCES navigation_closure_order (id),
  CONSTRAINT fk_disp_vessel FOREIGN KEY (vessel_id) REFERENCES vessel (id),
  CONSTRAINT fk_disp_plan FOREIGN KEY (sailing_plan_id) REFERENCES sailing_plan (id),
  CONSTRAINT fk_disp_berth FOREIGN KEY (berth_id) REFERENCES berth (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='封航返港处置项';

-- ============================================================================
-- 模块六：返港联系记录（每次联系的时间、结果、备注；靠泊成功也在此留一条）
--   client_request_id 是前端给每次操作生成的幂等令牌：同一处置项下重复提交
--   （双击 / 超时重试）命中 uk_disp_contact_req 直接返回首次结果，不产生第二条。
-- ============================================================================
CREATE TABLE disposition_contact (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  disposition_id    BIGINT       NOT NULL,
  contact_time      DATETIME     NOT NULL COMMENT '联系时间',
  result            VARCHAR(24)  NOT NULL COMMENT 'CONTACTED 已联系上/CONFIRMED_RETURN 确认返港/LOST_CONTACT 失联升级/REACHED 重新取得联系/BERTHED 已安全靠泊',
  remark            VARCHAR(256) NULL     COMMENT '备注',
  operator          VARCHAR(32)  NOT NULL COMMENT '值班员',
  client_request_id VARCHAR(64)  NULL     COMMENT '前端幂等令牌（同处置项内唯一）',
  created_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_disp_contact_req (disposition_id, client_request_id),
  KEY idx_contact_disp (disposition_id),
  CONSTRAINT fk_contact_disp FOREIGN KEY (disposition_id) REFERENCES return_disposition (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='返港处置联系记录';

-- ---------------------------- 封航操作日志 ----------------------------
-- 发布 / 到点生效 / 申请解除 / 归档 / 处置状态流转全部记一条，页面刷新、关页重开
-- 看到的就是同一份服务端事实；历史封航令的处置过程靠这张表 + 上面三张表完整回放。
CREATE TABLE closure_log (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  closure_order_id BIGINT       NOT NULL,
  action           VARCHAR(32)  NOT NULL COMMENT 'CREATE/ACTIVATE/SUSPEND_PLAN/BLOCKED_PLAN/RESTORE_PLANS/RELEASE/ARCHIVE/DISP_*',
  detail           VARCHAR(512) NOT NULL,
  operator         VARCHAR(32)  NOT NULL,
  created_at       DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_log_order (closure_order_id, id),
  CONSTRAINT fk_log_order FOREIGN KEY (closure_order_id) REFERENCES navigation_closure_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='封航操作日志';
