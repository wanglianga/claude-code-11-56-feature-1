# 海上风电故障码参考数据映射与诊断复现服务

把控制器告警、维保商工单术语和备件目录转成**可复演、可解释、可追溯**的故障诊断事实。

服务使用 **Java 21 + Spring Boot 3.5 + PostgreSQL 18**，把"来源码 / 统一概念 / 处置建议"分列建模，
并提供**两套并行的历史模型**：

| 模型 | 表 | 适用 | 代价 |
| --- | --- | --- | --- |
| **A. 有效期断言**（time-validity assertions） | `mapping_assertion` | 目录修订频繁、追溯修订少、要求灵活 | 查询时需按告警属性求当时适用关系，可能评估更多重叠断言 |
| **B. 发布快照**（release snapshot） | `release*` / `release_*` | 审计要求直接复演、吞吐稳定 | 每次发布冻结全量内容，发布与存储成本更高 |

两种模型都保证：结论可回查到**原始来源码、概念定义版本、审批记录**；且**都不会只保留当前码表**。

---

## 1. 关键业务语义（已实现并被测试覆盖）

1. **码表、概念、处置分列**：`source_code` / `term_attachment` → `mapping_assertion` → `concept` → `disposition_option`。
   一个来源码可在不同机型/固件下对应不同概念；一个概念可因库存与兼容条件产生不同可用建议。
2. **相同来源码收到相反映射时保留冲突**：两条 `ACTIVE` 断言并存，诊断返回 `CONFLICT`，**不静默取一**。
   只有管理员提交的**工程师裁定**（`mapping_adjudication`，带有效期与审批）才会显式打破平局，且不改写原断言。
3. **离线补传按发生时点解释**：解释时刻恒为 `occurredAt`；`receivedAt` 只记录迟到事实，
   `OFFLINE_BACKFILL` 只是传输属性，不改变语义。
4. **诊断运行中目录更新不得混入同一批结果**：每次运行在**评估第一个告警之前**固定一对
   catalog + inventory 快照，之后所有结果都引用它。
5. **互斥处置 → 结论待工程师裁定**：同一 `exclusive_group` 内有两个及以上可用选项时，结论为 `PENDING_ENGINEER`；
   不可用选项显式列在 `unavailable` 中，而不是被悄悄丢弃。
6. **固件/机型差异**：断言可按 `manufacturer / model / firmware 序号区间 / qualifiers` 限定；
   解析采用"最具体作用域优先"（机型 > 厂商 > 全局，固件区间与属性加分），其余低优先级候选被记录为 `suppressedBySpecificity`。

诊断结论状态：`RESOLVED` / `CONFLICT` / `PENDING_ENGINEER` / `NO_MAPPING` / `NO_DISPOSITION`。

---

## 2. 快速启动

### 2.1 Docker Compose（一键，含数据库与脱敏演示数据）

```bash
docker compose up --build
# 应用:      http://localhost:8080
# Swagger:   http://localhost:8080/swagger-ui.html
# OpenAPI:   http://localhost:8080/v3/api-docs
```

首次启动会自动执行 Flyway 建表并灌入演示数据（`APP_DEMO_ENABLED=true`）。

### 2.2 本地开发（使用容器内已预装的 PostgreSQL）

```bash
# 1) 建库（本机 PostgreSQL 18 已在 127.0.0.1:5432，账号 ccdev）
createdb -h 127.0.0.1 -U ccdev faultref        # 若不存在
# 2) 构建并启动
DB_URL=jdbc:postgresql://127.0.0.1:5432/faultref \
  /opt/maven/bin/mvn spring-boot:run
```

关键环境变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` | 数据库连接 |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` | 数据库账号 |
| `APP_SECURITY_ENABLED` | `true` | 关闭后所有接口开放（仅本地调试） |
| `APP_DEMO_ENABLED` | `true` | 启动时灌入演示数据（仅在库为空时生效） |
| `APP_DIAGNOSIS_MAX_BATCH` | `5000` | 单次诊断最大告警数 |

---

## 3. 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `viewer` | `viewer123` | VIEWER | 只读 |
| `engineer` | `engineer123` | ENGINEER | 只读 + 录入 + 运行诊断 |
| `admin` | `admin123` | ADMIN | 全部（含审批、裁定、发布 release） |

> 账号为演示用内存账号，生产部署请替换 `SecurityConfig` 中的 `UserDetailsService`。

---

## 4. 演示数据（覆盖题目要求的两类机组、目录、固件变更、离线补传）

- **两类机组**：NordWind `NW-8`（北区 A01/A02）与海鑫 `HX-6`（东区 B01/B02）。
- **相同液压异常、不同厂商代码不同**：`NORDWIND_CTRL/HYD-201` 与 `HAIXIN_CTRL/E4410` 都映射到"液压系统压力低"。
- **同码因固件改义**：`HYD-201` 在 `NW-8 V2.1` 起细分映射为"液压泵磨损"（`HYD_PUMP_WEAR`），此前为"液压系统压力低"。
- **维保商术语混称**：`振动超限` 同时映射到"齿轮箱振动超限"与"叶片不平衡" → **保留冲突**；
  仅对 `NW-8` 做了工程师裁定，`HX-6` 仍为 `CONFLICT`。
- **来源码相反映射**：`HAIXIN_CTRL/E5510` 两条 `ACTIVE` 断言互相矛盾 → 保留。
- **离线补传**：`ALT-NW-A01-20240115` 发生于 2024-01-15（当时 V1.0），2025-04-12 才上传 → 仍按 2024 时点解释。
- **目录演化**：`CAT-2023.01 → CAT-2025.01 → CAT-2025.02`，配合 `INV-*` 库存快照，
  使同一概念在不同时点分别得到"确认可用 / 互斥待裁定 / 无可用件"。
- **发布快照**：`REL-2025.01`、`REL-2025.02` 冻结全量内容并带 SHA-256 内容哈希。

---

## 5. 验证方式

### 5.1 自动化测试

```bash
# 使用独立测试库 faultref_test（不存在时先创建）
createdb -h 127.0.0.1 -U ccdev faultref_test
/opt/maven/bin/mvn test
```

覆盖 24 个用例：

- `DiagnosisScenarioTest`（10）：固件改义、离线补传、迟到但非补传、保留冲突、作用域优先、
  工程师裁定、互斥处置、库存不足、运行期目录固定、同码不同机型。
- `ReleaseSnapshotTest`（6）：冻结与审批回链、发布后目录变更不影响历史复演、草稿不可复演、
  模式/口径校验、内容哈希确定性、发布后禁止再改目录。
- `ApiContractTest`（8）：未认证 401、角色 403、校验错误结构、OpenAPI 公开、
  HTTP 运行诊断、补传时间校验。

### 5.2 端到端脚本（对着已启动的服务）

```bash
./scripts/verify.sh          # 30 项断言
# 或指定地址
BASE_URL=http://127.0.0.1:8080 ./scripts/verify.sh
```

覆盖：健康/OpenAPI、鉴权与角色、两类机型、同码改义、发生时点语义、保留冲突、可追溯性、
处置边界、目录固定、发布冻结与哈希、统计口径。

---

## 6. 主要 API

完整文档见 `http://localhost:8080/swagger-ui.html`。核心端点：

**参考数据**
```
POST /api/reference/manufacturers | /models | /firmware | /units
POST /api/reference/source-systems | /source-codes | /terms
POST /api/reference/concepts            创建概念（含 v1 定义）
POST /api/reference/concepts/{code}/definitions   追加已批准定义版本
GET  /api/reference/concepts/{code}      概念及定义历史
```

**映射断言（模型 A）**
```
POST /api/mappings/assertions                    新建断言（时间有效，不覆盖历史）
POST /api/mappings/assertions/{id}/close         撤回/废止并关闭有效期
GET  /api/mappings/source-codes/{id}/history     某来源码的全部断言与裁定
GET  /api/mappings/terms/{id}/history
GET  /api/mappings/conflicts                     当前保留冲突列表
POST /api/mappings/conflicts/{key}/adjudications 工程师裁定（admin）
```

**目录 / 库存 / 处置**
```
POST /api/catalog/versions ， POST /api/catalog/versions/{label}/publish
POST /api/catalog/parts | /entries | /dispositions
POST /api/catalog/inventory-snapshots
GET  /api/catalog/versions/{label}/dispositions
```

**证据（告警 / 工单）**
```
POST /api/evidence/alarms         occurredAt 决定解释时点；receivedAt 仅记录迟到
POST /api/evidence/work-orders
GET  /api/evidence/alarms | /work-orders | /work-orders/{id}
```

**诊断与复演**
```
POST /api/diagnosis/runs                         运行诊断
GET  /api/diagnosis/runs                         运行列表
GET  /api/diagnosis/runs/{id}                    结果与 findings
GET  /api/diagnosis/runs/{id}/statistics         统计与边界口径
GET  /api/diagnosis/runs/{left}/diff/{right}     两次运行逐告警差异
GET  /api/diagnosis/results/{id}/explain         回查原始来源/定义/审批/工单
```

**发布快照（模型 B）**
```
POST /api/releases                               创建草稿（绑定已发布目录 + 库存快照）
POST /api/releases/{label}/publish               冻结并计算内容哈希
GET  /api/releases | /{label} | /{label}/content
POST /api/releases/{label}/retire
```

### 6.1 诊断运行示例

```bash
# 断言模式，按“最新已发布目录”评估所有告警
curl -u engineer:engineer123 -X POST localhost:8080/api/diagnosis/runs \
  -H 'Content-Type: application/json' -d '{"mode":"ASSERTION","basis":"LATEST"}'

# 按业务时点复演（2024 年的补传告警按 2024 年的目录/映射解释）
curl -u engineer:engineer123 -X POST localhost:8080/api/diagnosis/runs \
  -H 'Content-Type: application/json' \
  -d '{"mode":"ASSERTION","basis":"AS_OF","asOf":"2024-02-01T00:00:00Z",
       "from":"2024-01-01T00:00:00Z","to":"2024-02-01T00:00:00Z"}'

# 快照模式，直接复演某个已发布 release
curl -u engineer:engineer123 -X POST localhost:8080/api/diagnosis/runs \
  -H 'Content-Type: application/json' \
  -d '{"mode":"SNAPSHOT","basis":"RELEASE","releaseLabel":"REL-2025.01",
       "from":"2025-04-01T00:00:00Z","to":"2025-05-01T00:00:00Z"}'
```

> **`knowledgeCutoff`（可选）**：仅用于"当时我们已知什么"的审计场景，按**审批时间**过滤断言；
> 它默认不启用，以免误把历史业务时点当成知识上限。

---

## 7. 两种历史模型的取舍

- 选 **A（断言）**：目录/术语修订频繁，追溯修订少，追求写入灵活与存储经济；
  代价是诊断需按告警属性做区间与作用域求值，重叠断言多时查询更重。本服务默认模式。
- 选 **B（快照）**：审计复演要求"直接、与后续修订无关"，诊断吞吐需稳定；
  代价是每次发布冻结全量（`release_code_entry` 等），发布流程与存储更高。
- 二者可**并存**：日常用 A 评估，关键结论发布为 B 以供长期审计复演；`GET /runs/{l}/diff/{r}` 可直接对比两模型结论差异。

具体选择由**目录修订频率、诊断吞吐、审计复演需求**共同决定，服务不强制其一，也不只保留当前码表。

---

## 8. 目录结构

```
src/main/java/com/windfarm/faultref/
  config/      SecurityConfig, OpenApiConfig
  domain/      (schema-first；实体以 SQL 表为准)
  repo/        ReferenceRepo, MappingRepo, CatalogRepo, EvidenceRepo, ReleaseRepo, DiagnosisRepo, Sql
  service/     ReferenceService, MappingService, CatalogService/CatalogAdminService,
               EvidenceService, DiagnosisService (引擎), DiagnosisQueryService (统计/差异/解释), ReleaseService
  seed/        DemoDataSeeder（可复现的演示数据与预置运行）
  web/         6 个 REST 控制器 + dto/
src/main/resources/db/migration/V1__schema.sql   Flyway 建表
scripts/verify.sh                                端到端验证（30 项）
Dockerfile / docker-compose.yml                  一键启动
```

---

## 9. 已执行的验证结论（如实记录）

- `mvn test`：24/24 通过（`DiagnosisScenarioTest` 10、`ReleaseSnapshotTest` 6、`ApiContractTest` 8），运行于独立库 `faultref_test`。
- `./scripts/verify.sh`：30/30 通过（对着本地启动的服务）。
- `mvn package` 产出的 `faultref-1.0.0.jar` 也曾以 `java -jar` 方式启动并跑通同一套 30 项验证。
- Docker / Docker Compose 未在本容器内执行（容器无 Docker daemon）；`Dockerfile` 与 `docker-compose.yml` 已提供，构建逻辑为 `mvn package` + Temurin 21 JRE 运行。
- 手工确认的关键输出：`HYD-201` 在 V2.1 解析为 `HYD_PUMP_WEAR`、V1.0 解析为 `HYD_LOW_PRESSURE`；
  2024 补传告警在 `AS_OF` 复演中仍为 `HYD_LOW_PRESSURE`；`振动超限` 在 `HX-6` 为 `CONFLICT`、
  在 `NW-8` 经裁定为 `GEARBOX_VIBRATION_HIGH`；2025-06 双泵库存下结论为 `PENDING_ENGINEER`。
