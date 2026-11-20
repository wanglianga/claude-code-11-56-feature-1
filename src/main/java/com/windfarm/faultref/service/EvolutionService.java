package com.windfarm.faultref.service;

import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.EvolutionRepo;
import com.windfarm.faultref.repo.EvidenceRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.Sql;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.EvolutionDtos;
import com.windfarm.faultref.web.dto.EvolutionDtos.BridgeView;
import com.windfarm.faultref.web.dto.EvolutionDtos.HierarchyView;
import com.windfarm.faultref.web.dto.EvolutionDtos.LcaResult;
import com.windfarm.faultref.web.dto.EvolutionDtos.TrendMarker;
import com.windfarm.faultref.web.dto.EvolutionDtos.TrendPoint;
import com.windfarm.faultref.web.dto.EvolutionDtos.TrendReport;
import com.windfarm.faultref.web.dto.EvolutionDtos.TrendSeries;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Concept-granularity evolution.
 *
 * Two explicit, selectable continuity strategies are supported; neither is
 * mandated as the only truth:
 *  - HIERARCHY keeps the versioned concept levels. A cross-version query walks
 *    the approved parent/child graph to the nearest common ancestor (LCA) and
 *    still reports the fine-code distribution inside it.
 *  - BRIDGE uses an engineer-approved bridge matrix to roll every code onto
 *    one coarse concept: reports are stable across firmware versions but the
 *    intra-fine distribution is deliberately masked.
 *
 * Hard rules:
 *  - only APPROVED hierarchy/bridge rows feed automatic statistics;
 *  - fine codes without an approved engineering definition are excluded;
 *  - alarms with missing firmware evidence stay "pending attribution" and
 *    never enter a statistic;
 *  - reopened work orders keep their original diagnosis and only gain an
 *    explicit link to the new-granularity explanation.
 */
@Service
public class EvolutionService {

    private final EvolutionRepo evolution;
    private final ReferenceRepo reference;
    private final EvidenceRepo evidence;

    public EvolutionService(EvolutionRepo evolution, ReferenceRepo reference, EvidenceRepo evidence) {
        this.evolution = evolution;
        this.reference = reference;
        this.evidence = evidence;
    }

    // ==================================================================
    // Hierarchy edges
    // ==================================================================

    @Transactional
    public UUID createHierarchy(ApiRequests.HierarchyCreate cmd) {
        UUID parentId = conceptId(cmd.parentConceptCode());
        UUID childId = conceptId(cmd.childConceptCode());
        if (parentId.equals(childId)) {
            throw ApiException.unprocessable("HIERARCHY_INVALID", "Parent and child concepts must differ");
        }
        Scope scope = resolveScope(cmd.manufacturerCode(), cmd.modelCode(),
                cmd.firmwareFrom(), cmd.firmwareTo());
        Instant validFrom = cmd.validFrom() == null ? Instant.now() : cmd.validFrom();
        if (cmd.validTo() != null && !cmd.validTo().isAfter(validFrom)) {
            throw ApiException.unprocessable("VALIDITY_INVALID", "validTo must be after validFrom");
        }
        return evolution.insertHierarchy(new MapSqlParameterSource()
                .addValue("parentId", parentId)
                .addValue("childId", childId)
                .addValue("relationshipType", cmd.relationshipType())
                .addValue("aggregationKind", cmd.aggregationKind())
                .addValue("modelId", scope.modelId())
                .addValue("firmwareFrom", cmd.firmwareFrom())
                .addValue("firmwareTo", cmd.firmwareTo())
                .addValue("firmwareFromSeq", scope.fromSeq())
                .addValue("firmwareToSeq", scope.toSeq())
                .addValue("basis", cmd.engineeringBasis())
                .addValue("status", "PROPOSED")
                .addValue("validFrom", Sql.odt(validFrom))
                .addValue("validTo", Sql.odt(cmd.validTo()))
                .addValue("approvedBy", null).addValue("approvalRef", null).addValue("approvedAt", null));
    }

    @Transactional
    public HierarchyView approveHierarchy(UUID id, String approvedBy, String approvalRef) {
        Map<String, Object> row = ReferenceRepo.require(evolution.hierarchyById(id),
                "HIERARCHY_NOT_FOUND", "Unknown hierarchy edge " + id);
        if (!"PROPOSED".equals(ReferenceRepo.asString(row, "status"))) {
            throw ApiException.conflict("HIERARCHY_NOT_PROPOSED",
                    "Hierarchy edge " + id + " is " + ReferenceRepo.asString(row, "status")
                            + "; only PROPOSED edges can be approved");
        }
        evolution.approveHierarchy(id, approvedBy, approvalRef, Instant.now());
        return toHierarchyView(ReferenceRepo.require(evolution.hierarchyById(id),
                "HIERARCHY_NOT_FOUND", "Missing approved edge"));
    }

    @Transactional
    public void retireHierarchy(UUID id) {
        ReferenceRepo.require(evolution.hierarchyById(id), "HIERARCHY_NOT_FOUND",
                "Unknown hierarchy edge " + id);
        evolution.retireHierarchy(id);
    }

    public List<HierarchyView> listHierarchy(String status) {
        return evolution.listHierarchy(status).stream().map(this::toHierarchyView).toList();
    }

    public EvolutionDtos.GranularityView granularityOf(String conceptCode) {
        Map<String, Object> concept = ReferenceRepo.require(reference.conceptByCode(conceptCode),
                "CONCEPT_NOT_FOUND", "Unknown concept " + conceptCode);
        UUID conceptId = ReferenceRepo.asUuid(concept, "id");
        List<HierarchyView> edges = evolution.hierarchyForConcept(conceptId).stream()
                .map(this::toHierarchyView).toList();
        List<BridgeView> bridges = evolution.listBridges(null).stream()
                .map(this::toBridgeView)
                .filter(b -> conceptCode.equals(b.sourceConceptCode())
                        || conceptCode.equals(b.targetConceptCode()))
                .toList();
        List<Map<String, Object>> warnings = evolution.undefinedChildConcepts().stream()
                .filter(r -> conceptCode.equals(ReferenceRepo.asString(r, "code"))
                        || edges.stream().anyMatch(e -> conceptCode.equals(e.parentConceptCode())
                                && ReferenceRepo.asString(r, "code").equals(e.childConceptCode())))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("conceptCode", ReferenceRepo.asString(r, "code"));
                    m.put("conceptName", ReferenceRepo.asString(r, "name"));
                    m.put("warning", "SUBCODE_WITHOUT_APPROVED_DEFINITION");
                    m.put("message", "Fine code has no APPROVED hierarchy edge and is excluded from "
                            + "automatic statistics until an engineering definition is approved");
                    return m;
                }).toList();
        return new EvolutionDtos.GranularityView(conceptCode, ReferenceRepo.asString(concept, "name"),
                edges, bridges, warnings);
    }

    // ==================================================================
    // Bridge matrix
    // ==================================================================

    @Transactional
    public UUID createBridge(ApiRequests.BridgeCreate cmd) {
        UUID sourceId = conceptId(cmd.sourceConceptCode());
        UUID targetId = conceptId(cmd.targetConceptCode());
        if (sourceId.equals(targetId)) {
            throw ApiException.unprocessable("BRIDGE_INVALID", "Bridge source and target concepts must differ");
        }
        Scope scope = resolveScope(cmd.manufacturerCode(), cmd.modelCode(),
                cmd.firmwareFrom(), cmd.firmwareTo());
        BigDecimal weight = cmd.weight() == null ? BigDecimal.ONE : BigDecimal.valueOf(cmd.weight());
        if (weight.signum() <= 0) {
            throw ApiException.unprocessable("BRIDGE_WEIGHT_INVALID", "weight must be positive");
        }
        Instant validFrom = cmd.validFrom() == null ? Instant.now() : cmd.validFrom();
        if (cmd.validTo() != null && !cmd.validTo().isAfter(validFrom)) {
            throw ApiException.unprocessable("VALIDITY_INVALID", "validTo must be after validFrom");
        }
        return evolution.insertBridge(new MapSqlParameterSource()
                .addValue("matrixCode", cmd.matrixCode())
                .addValue("sourceId", sourceId)
                .addValue("targetId", targetId)
                .addValue("modelId", scope.modelId())
                .addValue("firmwareFrom", cmd.firmwareFrom())
                .addValue("firmwareTo", cmd.firmwareTo())
                .addValue("firmwareFromSeq", scope.fromSeq())
                .addValue("firmwareToSeq", scope.toSeq())
                .addValue("weight", weight)
                .addValue("basis", cmd.engineeringBasis())
                .addValue("status", "PROPOSED")
                .addValue("validFrom", Sql.odt(validFrom))
                .addValue("validTo", Sql.odt(cmd.validTo()))
                .addValue("approvedBy", null).addValue("approvalRef", null).addValue("approvedAt", null));
    }

    @Transactional
    public BridgeView approveBridge(UUID id, String approvedBy, String approvalRef) {
        Map<String, Object> row = ReferenceRepo.require(evolution.bridgeById(id),
                "BRIDGE_NOT_FOUND", "Unknown bridge row " + id);
        if (!"PROPOSED".equals(ReferenceRepo.asString(row, "status"))) {
            throw ApiException.conflict("BRIDGE_NOT_PROPOSED",
                    "Bridge row " + id + " is " + ReferenceRepo.asString(row, "status")
                            + "; only PROPOSED rows can be approved");
        }
        evolution.approveBridge(id, approvedBy, approvalRef, Instant.now());
        return toBridgeView(ReferenceRepo.require(evolution.bridgeById(id),
                "BRIDGE_NOT_FOUND", "Missing approved bridge"));
    }

    @Transactional
    public void retireBridge(UUID id) {
        ReferenceRepo.require(evolution.bridgeById(id), "BRIDGE_NOT_FOUND", "Unknown bridge row " + id);
        evolution.retireBridge(id);
    }

    public List<BridgeView> listBridges(String status) {
        return evolution.listBridges(status).stream().map(this::toBridgeView).toList();
    }

    // ==================================================================
    // Unattributed alarms (missing firmware evidence)
    // ==================================================================

    @Transactional
    public int detectMissingFirmwareEvidence() {
        // The alarm table itself is the evidence ledger: a null firmware_version_id
        // means neither the controller report nor the install-window lookup could
        // establish the firmware, so the alarm cannot be placed on either side of
        // a firmware split.
        List<Map<String, Object>> rows = evidence.listAlarms();
        int flagged = 0;
        for (Map<String, Object> alarm : rows) {
            if (ReferenceRepo.asUuid(alarm, "firmware_version_id") == null) {
                evolution.upsertUnattributed(ReferenceRepo.asUuid(alarm, "id"),
                        "FIRMWARE_EVIDENCE_MISSING", null,
                        "No firmware version reported and no install window covers the occurrence instant");
                flagged++;
            }
        }
        return flagged;
    }

    @Transactional
    public EvolutionDtos.UnattributedView flagUnattributed(String alarmExternalId, String reasonCode,
                                                           String rawFirmwareHint, String note) {
        Map<String, Object> alarm = ReferenceRepo.require(evidence.alarmByExternalId(alarmExternalId),
                "ALARM_NOT_FOUND", "Unknown alarm " + alarmExternalId);
        String reason = reasonCode == null ? "FIRMWARE_EVIDENCE_MISSING" : reasonCode;
        if (!List.of("FIRMWARE_EVIDENCE_MISSING", "AMBIGUOUS_FIRMWARE_WINDOW").contains(reason)) {
            throw ApiException.badRequest("REASON_INVALID",
                    "reasonCode must be FIRMWARE_EVIDENCE_MISSING or AMBIGUOUS_FIRMWARE_WINDOW");
        }
        evolution.upsertUnattributed(ReferenceRepo.asUuid(alarm, "id"), reason, rawFirmwareHint, note);
        return evolution.listUnattributed(false).stream()
                .filter(u -> alarmExternalId.equals(ReferenceRepo.asString(u, "external_id")))
                .map(this::toUnattributedView).findFirst()
                .orElseThrow(() -> ApiException.conflict("UNATTRIBUTED_NOT_FOUND", "Flagging failed"));
    }

    public List<EvolutionDtos.UnattributedView> listUnattributed(boolean openOnly) {
        return evolution.listUnattributed(openOnly).stream().map(this::toUnattributedView).toList();
    }

    @Transactional
    public EvolutionDtos.UnattributedView resolveUnattributed(UUID id, String resolutionNote) {
        ReferenceRepo.require(evolution.unattributedById(id), "UNATTRIBUTED_NOT_FOUND",
                "Unknown unattributed record " + id);
        evolution.resolveUnattributed(id, resolutionNote, Instant.now());
        return toUnattributedView(ReferenceRepo.require(evolution.unattributedById(id),
                "UNATTRIBUTED_NOT_FOUND", "Missing resolved record"));
    }

    // ==================================================================
    // Reopened work order granularity links
    // ==================================================================

    @Transactional
    public EvolutionDtos.WorkOrderLinkView linkWorkOrder(UUID workOrderId, ApiRequests.WorkOrderLinkCreate cmd,
                                                         String user) {
        Map<String, Object> wo = ReferenceRepo.require(evidence.workOrderById(workOrderId),
                "WORK_ORDER_NOT_FOUND", "Unknown work order " + workOrderId);
        Map<String, Object> concept = ReferenceRepo.require(reference.conceptByCode(cmd.conceptCode()),
                "CONCEPT_NOT_FOUND", "Unknown concept " + cmd.conceptCode());
        String linkType = cmd.linkType() == null ? "REOPENED_GRANULARITY_NOTE" : cmd.linkType();
        if (!List.of("REOPENED_GRANULARITY_NOTE", "HISTORICAL_REFERENCE").contains(linkType)) {
            throw ApiException.badRequest("LINK_TYPE_INVALID",
                    "linkType must be REOPENED_GRANULARITY_NOTE or HISTORICAL_REFERENCE");
        }
        UUID id = evolution.insertWorkOrderLink(new MapSqlParameterSource()
                .addValue("workOrderId", ReferenceRepo.asUuid(wo, "id"))
                .addValue("conceptId", ReferenceRepo.asUuid(concept, "id"))
                .addValue("linkType", linkType)
                .addValue("note", cmd.note())
                .addValue("createdBy", user == null ? "system" : user));
        Map<String, Object> row = evolution.linksForWorkOrder(workOrderId).stream()
                .filter(r -> id.equals(ReferenceRepo.asUuid(r, "id"))).findFirst()
                .orElseThrow(() -> ApiException.conflict("LINK_NOT_FOUND", "Link insert failed"));
        return toLinkView(row);
    }

    public List<EvolutionDtos.WorkOrderLinkView> linksForWorkOrder(UUID workOrderId) {
        return evolution.linksForWorkOrder(workOrderId).stream().map(this::toLinkView).toList();
    }

    // ==================================================================
    // Nearest common ancestor comparison
    // ==================================================================

    public LcaResult compareConcepts(String codeA, String codeB) {
        ReferenceRepo.require(reference.conceptByCode(codeA), "CONCEPT_NOT_FOUND", "Unknown concept " + codeA);
        ReferenceRepo.require(reference.conceptByCode(codeB), "CONCEPT_NOT_FOUND", "Unknown concept " + codeB);
        Graph graph = Graph.approved(evolution.listHierarchy("APPROVED"));
        return lca(graph, codeA, codeB);
    }

    private LcaResult lca(Graph graph, String codeA, String codeB) {
        List<String> pathA = graph.parentPath(codeA);
        List<String> pathB = graph.parentPath(codeB);
        Set<String> ancestorsA = new LinkedHashSet<>(graph.ancestors(codeA));
        ancestorsA.add(codeA);

        if (codeA.equals(codeB)) {
            return new LcaResult(codeA, codeB, "FINE_GRAINED", codeA, nameOf(codeA), 0, 0,
                    List.of(codeA), List.of(codeA), List.of(), true,
                    "Same fine-grained concept on both firmware versions: directly comparable at fine level");
        }
        String lca = null;
        for (String c : graph.ancestors(codeB)) {
            if (ancestorsA.contains(c)) {
                lca = c;
                break;
            }
        }
        if (lca == null && ancestorsA.contains(codeB)) {
            lca = codeB;
        }
        if (lca == null) {
            // Also check B itself being an ancestor of A is covered above; disjoint graphs remain.
            return new LcaResult(codeA, codeB, "NOT_COMPARABLE", null, null, pathA.size(), pathB.size(),
                    pathA, pathB, List.of(), false,
                    "No common ancestor concept exists in the approved hierarchy; the two codes cannot be "
                            + "compared continuously without an engineer-approved bridge");
        }
        List<String> edgesOnPaths = graph.edgesOnPaths(codeA, codeB, lca);
        List<String> kinds = graph.aggregationKinds(edgesOnPaths);
        boolean reversible = kinds.stream().allMatch("REVERSIBLE"::equals);
        // A fine-grained comparison requires both codes to be sibling children
        // produced by the same SPLIT. A REFINE edge (the old coarse code kept as
        // a concept) means the two sides sit at different granularity, so the
        // comparison is honestly a common-ancestor one.
        boolean siblingSplits = graph.parents(codeA).contains(lca) && graph.parents(codeB).contains(lca)
                && graph.isSplitEdge(lca, codeA) && graph.isSplitEdge(lca, codeB);
        String kind = siblingSplits ? "FINE_GRAINED" : "COMMON_ANCESTOR";
        String explanation = siblingSplits
                ? "Both codes are fine-grained SPLIT children of " + lca + ": the split is compared at fine level"
                : "Continuity is available only at the nearest common ancestor " + lca
                        + "; fine-level distributions inside it are reported separately";
        return new LcaResult(codeA, codeB, kind, lca, nameOf(lca), pathA.size(), pathB.size(),
                pathA, pathB, kinds, reversible, explanation);
    }

    // ==================================================================
    // Cross-version trend report
    // ==================================================================

    @Transactional(readOnly = true)
    public TrendReport trend(ApiRequests.TrendRequest req) {
        List<UUID> runIds = req.runIds() == null ? List.of() : req.runIds();
        if (runIds.isEmpty()) {
            throw ApiException.badRequest("RUN_IDS_REQUIRED", "At least one runId is required");
        }
        String period = req.period() == null ? "MONTH" : req.period().toUpperCase();
        if (!List.of("MONTH", "QUARTER", "YEAR").contains(period)) {
            throw ApiException.badRequest("PERIOD_INVALID", "period must be MONTH, QUARTER or YEAR");
        }
        String mode = req.mode() == null ? "HIERARCHY" : req.mode().toUpperCase();
        if (!List.of("HIERARCHY", "BRIDGE").contains(mode)) {
            throw ApiException.badRequest("MODE_INVALID", "mode must be HIERARCHY or BRIDGE");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (UUID runId : runIds) {
            rows.addAll(evolution.runAlarmConcepts(runId));
        }
        // Deterministic, de-duplicated per-alarm view (same alarm may appear in several runs).
        Map<UUID, Map<String, Object>> byAlarm = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byAlarm.putIfAbsent(ReferenceRepo.asUuid(row, "alarm_id"), row);
        }

        List<Map<String, Object>> unattributedRows = new ArrayList<>();
        Map<String, Long> undefinedCounts = new LinkedHashMap<>();
        Map<String, String> undefinedNames = new LinkedHashMap<>();
        Map<String, Long> unbridgedCounts = new LinkedHashMap<>();
        Map<String, String> unbridgedNames = new LinkedHashMap<>();
        long excluded = 0;
        long noPrimary = 0;
        List<Map<String, Object>> statRows = new ArrayList<>();

        for (Map<String, Object> row : byAlarm.values()) {
            Instant occurred = ReferenceRepo.asInstant(row, "occurred_at");
            if (req.from() != null && occurred != null && occurred.isBefore(req.from())) {
                continue;
            }
            if (req.to() != null && occurred != null && !occurred.isBefore(req.to())) {
                continue;
            }
            if (Boolean.TRUE.equals(row.get("unattributed"))) {
                unattributedRows.add(row);
                excluded++;
                continue;
            }
            String conceptCode = ReferenceRepo.asString(row, "concept_code");
            if (conceptCode == null) {
                noPrimary++;
                continue;
            }
            if (Boolean.TRUE.equals(row.get("undefined_subcode"))) {
                undefinedCounts.merge(conceptCode, 1L, Long::sum);
                undefinedNames.putIfAbsent(conceptCode, ReferenceRepo.asString(row, "concept_name"));
                excluded++;
                continue;
            }
            statRows.add(row);
        }

        Graph graph = Graph.approved(evolution.listHierarchy("APPROVED"));
        List<Map<String, Object>> bridgeRows = evolution.listBridges("APPROVED");
        String graphSource = "LIVE_APPROVED";

        List<TrendSeries> series = "BRIDGE".equals(mode)
                ? bridgeSeries(statRows, bridgeRows, period, unbridgedCounts, unbridgedNames)
                : hierarchySeries(statRows, graph, period, req.conceptCode());

        // Excluded alarms that could not be placed anywhere still count as exclusions.
        long unbridgedTotal = unbridgedCounts.values().stream().mapToLong(Long::longValue).sum();
        excluded += unbridgedTotal;
        // Alarms that actually entered a series: bridge mode drops the unbridged
        // ones, hierarchy mode keeps every defined concept.
        long enteredStatistics = "BRIDGE".equals(mode)
                ? statRows.size() - unbridgedTotal
                : statRows.size();

        // Re-read the full unattributed records so the report carries reason, note
        // and resolution state rather than only the trend row projection.
        Set<UUID> unattributedAlarmIds = unattributedRows.stream()
                .map(r -> ReferenceRepo.asUuid(r, "alarm_id")).collect(java.util.stream.Collectors.toSet());
        List<EvolutionDtos.UnattributedView> unattributed = evolution.listUnattributed(false).stream()
                .filter(u -> unattributedAlarmIds.contains(ReferenceRepo.asUuid(u, "alarm_id")))
                .map(this::toUnattributedView)
                .toList();

        List<EvolutionDtos.ExclusionCount> undefinedList = undefinedCounts.entrySet().stream()
                .map(e -> new EvolutionDtos.ExclusionCount(e.getKey(), undefinedNames.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(EvolutionDtos.ExclusionCount::conceptCode)).toList();
        List<EvolutionDtos.ExclusionCount> unbridgedList = unbridgedCounts.entrySet().stream()
                .map(e -> new EvolutionDtos.ExclusionCount(e.getKey(), unbridgedNames.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(EvolutionDtos.ExclusionCount::conceptCode)).toList();

        long considered = enteredStatistics + excluded + noPrimary;
        String explanation = buildExplanation(mode, period, enteredStatistics, excluded, noPrimary,
                unattributed.size(), undefinedList.size(), unbridgedList.size());
        return new TrendReport(mode, period, graphSource, runIds, req.from(), req.to(),
                considered, excluded, series, unattributed, undefinedList, unbridgedList, explanation);
    }

    private List<TrendSeries> hierarchySeries(List<Map<String, Object>> rows, Graph graph, String period,
                                              String focusConcept) {
        // Bucket every included alarm by period and fine concept.
        TreeMap<String, Map<String, Long>> buckets = new TreeMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String code = ReferenceRepo.asString(row, "concept_code");
            names.putIfAbsent(code, ReferenceRepo.asString(row, "concept_name"));
            buckets.computeIfAbsent(periodKey(ReferenceRepo.asInstant(row, "occurred_at"), period),
                            k -> new LinkedHashMap<>())
                    .merge(code, 1L, Long::sum);
        }

        record Group(String key, String name, String kind, Set<String> members) {
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        if (focusConcept != null) {
            Set<String> members = new LinkedHashSet<>(graph.descendants(focusConcept));
            members.add(focusConcept);
            String kind = graph.children(focusConcept).isEmpty() ? "FINE_GRAINED" : "COMMON_ANCESTOR";
            groups.put(focusConcept, new Group(focusConcept, nameOf(focusConcept), kind, members));
        } else {
            for (String code : names.keySet()) {
                List<String> path = graph.parentPath(code);
                String root = path.isEmpty() ? code : path.get(path.size() - 1);
                Group g = groups.get(root);
                if (g == null) {
                    boolean isolated = graph.parents(code).isEmpty() && graph.children(code).isEmpty();
                    groups.put(root, new Group(root, nameOf(root),
                            isolated ? "FINE_GRAINED" : "COMMON_ANCESTOR", new LinkedHashSet<>()));
                    g = groups.get(root);
                }
                g.members().add(code);
            }
        }

        List<TrendSeries> out = new ArrayList<>();
        for (Group g : groups.values().stream().sorted(Comparator.comparing(Group::key)).toList()) {
            List<String> aggKinds = graph.aggregationKindsWithin(g.members());
            String agg = aggKinds.isEmpty() ? "NOT_AGGREGATED"
                    : (aggKinds.stream().distinct().count() == 1 ? aggKinds.get(0) : "MIXED");
            List<TrendPoint> points = new ArrayList<>();
            String previousPeriod = null;
            Map<String, Long> previousDist = null;
            List<TrendMarker> markers = new ArrayList<>();
            for (Map.Entry<String, Map<String, Long>> e : buckets.entrySet()) {
                Map<String, Long> dist = new LinkedHashMap<>();
                long total = 0;
                for (String member : g.members().stream().sorted().toList()) {
                    long n = e.getValue().getOrDefault(member, 0L);
                    if (n > 0) {
                        dist.put(member, n);
                    }
                    total += n;
                }
                points.add(new TrendPoint(e.getKey(), total, dist, true,
                        "Fine-code distribution preserved under ancestor " + g.key()));
                if (previousPeriod != null) {
                    markers.add(new TrendMarker(previousPeriod, e.getKey(), "CONTINUOUS",
                            "Comparable at " + g.kind() + " level via hierarchy"));
                    if (!Objects.equals(previousDist, dist)) {
                        markers.add(new TrendMarker(previousPeriod, e.getKey(), "FINE_DISTRIBUTION_SHIFT",
                                "Fine-code shares moved inside the common ancestor; detail is retained"));
                    }
                }
                previousPeriod = e.getKey();
                previousDist = dist;
            }
            if ("IRREVERSIBLE".equals(agg) || "MIXED".equals(agg)) {
                markers.add(0, new TrendMarker(null, null, "IRREVERSIBLE_AGGREGATION_PRESENT",
                        "At least one split edge is IRREVERSIBLE: rolling fine codes back to the parent "
                                + "loses information; ancestor totals are indicative, not equivalent"));
            }
            out.add(new TrendSeries(g.key(), g.name(), g.kind(),
                    agg, false, g.members().stream().sorted().toList(), points, markers));
        }
        return out;
    }

    private List<TrendSeries> bridgeSeries(List<Map<String, Object>> rows, List<Map<String, Object>> bridges,
                                           String period, Map<String, Long> unbridgedCounts,
                                           Map<String, String> unbridgedNames) {
        // Deterministic effective bridge per source: highest approved weight,
        // tie broken by target code. Multi-target proportional allocation is
        // intentionally not guessed; the matrix rows and weights stay visible.
        Map<String, Map<String, Object>> effective = new LinkedHashMap<>();
        for (Map<String, Object> b : bridges) {
            String source = ReferenceRepo.asString(b, "source_concept_code");
            Map<String, Object> existing = effective.get(source);
            if (existing == null || ((BigDecimal) b.get("weight")).compareTo(
                    (BigDecimal) existing.get("weight")) > 0
                    || (Objects.equals(b.get("weight"), existing.get("weight"))
                        && ReferenceRepo.asString(b, "target_concept_code")
                            .compareTo(ReferenceRepo.asString(existing, "target_concept_code")) < 0)) {
                effective.put(source, b);
            }
        }

        TreeMap<String, Map<String, Long>> buckets = new TreeMap<>();
        Map<String, Set<String>> membersByTarget = new LinkedHashMap<>();
        Map<String, String> targetNames = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String code = ReferenceRepo.asString(row, "concept_code");
            Map<String, Object> bridge = effective.get(code);
            if (bridge == null) {
                unbridgedCounts.merge(code, 1L, Long::sum);
                unbridgedNames.putIfAbsent(code, ReferenceRepo.asString(row, "concept_name"));
                continue;
            }
            String target = ReferenceRepo.asString(bridge, "target_concept_code");
            targetNames.putIfAbsent(target, ReferenceRepo.asString(bridge, "target_concept_name"));
            membersByTarget.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(code);
            buckets.computeIfAbsent(periodKey(ReferenceRepo.asInstant(row, "occurred_at"), period),
                            k -> new LinkedHashMap<>())
                    .merge(target, 1L, Long::sum);
        }

        List<TrendSeries> out = new ArrayList<>();
        for (String target : targetNames.keySet().stream().sorted().toList()) {
            Set<String> members = membersByTarget.getOrDefault(target, Set.of());
            List<TrendPoint> points = new ArrayList<>();
            String previousPeriod = null;
            List<TrendMarker> markers = new ArrayList<>();
            for (Map.Entry<String, Map<String, Long>> e : buckets.entrySet()) {
                long total = e.getValue().getOrDefault(target, 0L);
                points.add(new TrendPoint(e.getKey(), total,
                        total == 0 ? Map.of() : Map.of(target, total), false,
                        "Bridge matrix rolls " + members.size() + " code(s) onto " + target
                                + "; fine-level internal change is masked by design"));
                if (previousPeriod != null) {
                    markers.add(new TrendMarker(previousPeriod, e.getKey(), "CONTINUOUS_BRIDGED",
                            "Stable coarse-grained continuity via engineer-approved bridge matrix"));
                }
                previousPeriod = e.getKey();
            }
            markers.add(0, new TrendMarker(null, null, "FINE_DETAIL_MASKED",
                    "Bridge reports are stable but cannot reveal shifts between the member fine codes: "
                            + members));
            out.add(new TrendSeries(target, targetNames.get(target), "COMMON_ANCESTOR", "BRIDGED",
                    true, members.stream().sorted().toList(), points, markers));
        }
        if (!unbridgedCounts.isEmpty()) {
            // A standalone marker so the report states the limit explicitly.
            out.add(new TrendSeries("UNBRIDGED", "Concepts without an approved bridge",
                    "NOT_COMPARABLE", "NONE", true, unbridgedCounts.keySet().stream().sorted().toList(),
                    List.of(), List.of(new TrendMarker(null, null, "NOT_COMPARABLE",
                            "No approved bridge exists for these concepts; their alarms are excluded "
                                    + "from bridged statistics rather than guessed"))));
        }
        return out;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private String buildExplanation(String mode, String period, long included, long excluded, long noPrimary,
                                    long unattributed, long undefined, long unbridged) {
        return "Trend over " + period + " buckets using " + mode
                + " strategy: " + included + " alarm(s) entered statistics, " + excluded
                + " were excluded (" + unattributed + " pending firmware attribution, " + undefined
                + " fine code(s) without approved definition, " + unbridged
                + " alarm(s) without an approved bridge where applicable); " + noPrimary
                + " alarm(s) had no single primary concept (conflict/no-mapping) and are not concept statistics.";
    }

    private String periodKey(Instant at, String period) {
        if (at == null) {
            return "UNKNOWN";
        }
        java.time.ZonedDateTime z = at.atZone(java.time.ZoneOffset.UTC);
        return switch (period) {
            case "YEAR" -> String.valueOf(z.getYear());
            case "QUARTER" -> z.getYear() + "-Q" + ((z.getMonthValue() - 1) / 3 + 1);
            default -> String.format("%04d-%02d", z.getYear(), z.getMonthValue());
        };
    }

    private UUID conceptId(String code) {
        Map<String, Object> c = ReferenceRepo.require(reference.conceptByCode(code), "CONCEPT_NOT_FOUND",
                "Unknown concept " + code);
        return ReferenceRepo.asUuid(c, "id");
    }

    private Scope resolveScope(String manufacturerCode, String modelCode, String firmwareFrom, String firmwareTo) {
        UUID modelId = null;
        Integer fromSeq = null;
        Integer toSeq = null;
        if (modelCode != null) {
            String man = manufacturerCode == null ? "" : manufacturerCode;
            Map<String, Object> model = ReferenceRepo.require(
                    reference.modelByCode(man, modelCode), "MODEL_NOT_FOUND",
                    "Unknown model " + man + "/" + modelCode);
            modelId = ReferenceRepo.asUuid(model, "id");
        } else if (manufacturerCode != null) {
            throw ApiException.badRequest("MODEL_SCOPE_INVALID", "modelCode is required when manufacturerCode is set");
        }
        if (firmwareFrom != null) {
            if (modelId == null) {
                throw ApiException.badRequest("FIRMWARE_SCOPE_INVALID", "Firmware bounds require a model scope");
            }
            Map<String, Object> fw = ReferenceRepo.require(
                    reference.firmwareByModelAndVersion(modelId, firmwareFrom),
                    "FIRMWARE_NOT_FOUND", "Unknown firmware " + firmwareFrom);
            fromSeq = ReferenceRepo.asInt(fw, "sequence");
        }
        if (firmwareTo != null) {
            if (modelId == null) {
                throw ApiException.badRequest("FIRMWARE_SCOPE_INVALID", "Firmware bounds require a model scope");
            }
            Map<String, Object> fw = ReferenceRepo.require(
                    reference.firmwareByModelAndVersion(modelId, firmwareTo),
                    "FIRMWARE_NOT_FOUND", "Unknown firmware " + firmwareTo);
            toSeq = ReferenceRepo.asInt(fw, "sequence");
        }
        if (fromSeq != null && toSeq != null && fromSeq > toSeq) {
            throw ApiException.unprocessable("FIRMWARE_RANGE_INVALID",
                    "firmwareFrom sequence " + fromSeq + " is after firmwareTo sequence " + toSeq);
        }
        return new Scope(modelId, fromSeq, toSeq);
    }

    private String nameOf(String code) {
        return reference.conceptByCode(code).map(c -> ReferenceRepo.asString(c, "name")).orElse(code);
    }

    private HierarchyView toHierarchyView(Map<String, Object> r) {
        return new HierarchyView(
                ReferenceRepo.asUuid(r, "id"),
                ReferenceRepo.asString(r, "parent_concept_code"),
                ReferenceRepo.asString(r, "parent_concept_name"),
                ReferenceRepo.asString(r, "child_concept_code"),
                ReferenceRepo.asString(r, "child_concept_name"),
                ReferenceRepo.asString(r, "relationship_type"),
                ReferenceRepo.asString(r, "aggregation_kind"),
                ReferenceRepo.asString(r, "manufacturer_code"),
                ReferenceRepo.asString(r, "model_code"),
                ReferenceRepo.asString(r, "firmware_from"),
                ReferenceRepo.asString(r, "firmware_to"),
                ReferenceRepo.asInt(r, "firmware_from_seq"),
                ReferenceRepo.asInt(r, "firmware_to_seq"),
                ReferenceRepo.asString(r, "engineering_basis"),
                ReferenceRepo.asString(r, "status"),
                ReferenceRepo.asInstant(r, "valid_from"),
                ReferenceRepo.asInstant(r, "valid_to"),
                ReferenceRepo.asString(r, "approved_by"),
                ReferenceRepo.asString(r, "approval_ref"),
                ReferenceRepo.asInstant(r, "approved_at"));
    }

    private BridgeView toBridgeView(Map<String, Object> r) {
        return new BridgeView(
                ReferenceRepo.asUuid(r, "id"),
                ReferenceRepo.asString(r, "matrix_code"),
                ReferenceRepo.asString(r, "source_concept_code"),
                ReferenceRepo.asString(r, "source_concept_name"),
                ReferenceRepo.asString(r, "target_concept_code"),
                ReferenceRepo.asString(r, "target_concept_name"),
                ReferenceRepo.asString(r, "model_code"),
                ReferenceRepo.asString(r, "firmware_from"),
                ReferenceRepo.asString(r, "firmware_to"),
                ReferenceRepo.asInt(r, "firmware_from_seq"),
                ReferenceRepo.asInt(r, "firmware_to_seq"),
                Sql.decimal(r.get("weight")),
                ReferenceRepo.asString(r, "engineering_basis"),
                ReferenceRepo.asString(r, "status"),
                ReferenceRepo.asInstant(r, "valid_from"),
                ReferenceRepo.asInstant(r, "valid_to"),
                ReferenceRepo.asString(r, "approved_by"),
                ReferenceRepo.asString(r, "approval_ref"),
                ReferenceRepo.asInstant(r, "approved_at"));
    }

    private EvolutionDtos.UnattributedView toUnattributedView(Map<String, Object> r) {
        return new EvolutionDtos.UnattributedView(
                ReferenceRepo.asUuid(r, "id"),
                ReferenceRepo.asUuid(r, "alarm_id"),
                ReferenceRepo.asString(r, "external_id"),
                ReferenceRepo.asString(r, "unit_code"),
                ReferenceRepo.asString(r, "source_system_code"),
                ReferenceRepo.asString(r, "raw_code"),
                ReferenceRepo.asString(r, "reason_code"),
                ReferenceRepo.asString(r, "raw_firmware_hint"),
                ReferenceRepo.asString(r, "note"),
                ReferenceRepo.asInstant(r, "occurred_at"),
                ReferenceRepo.asInstant(r, "detected_at"),
                ReferenceRepo.asInstant(r, "resolved_at"),
                ReferenceRepo.asString(r, "resolution_note"));
    }

    private EvolutionDtos.WorkOrderLinkView toLinkView(Map<String, Object> r) {
        return new EvolutionDtos.WorkOrderLinkView(
                ReferenceRepo.asUuid(r, "id"),
                ReferenceRepo.asUuid(r, "work_order_id"),
                ReferenceRepo.asString(r, "concept_code"),
                ReferenceRepo.asString(r, "concept_name"),
                ReferenceRepo.asString(r, "link_type"),
                ReferenceRepo.asString(r, "note"),
                ReferenceRepo.asString(r, "created_by"),
                ReferenceRepo.asInstant(r, "created_at"));
    }

    private record Scope(UUID modelId, Integer fromSeq, Integer toSeq) {
    }

    /** In-memory approved hierarchy graph with parent/child traversal and LCA. */
    static final class Graph {
        private final Map<String, List<String>> parents = new LinkedHashMap<>();
        private final Map<String, List<String>> children = new LinkedHashMap<>();
        private final Map<String, String> edgeAggregation = new LinkedHashMap<>();
        private final Map<String, String> edgeRelationship = new LinkedHashMap<>();

        private Graph() {
        }

        static Graph approved(List<Map<String, Object>> rows) {
            Graph g = new Graph();
            for (Map<String, Object> r : rows) {
                String parent = ReferenceRepo.asString(r, "parent_concept_code");
                String child = ReferenceRepo.asString(r, "child_concept_code");
                g.parents.computeIfAbsent(child, k -> new ArrayList<>()).add(parent);
                g.children.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
                g.edgeAggregation.put(parent + "->" + child, ReferenceRepo.asString(r, "aggregation_kind"));
                g.edgeRelationship.put(parent + "->" + child, ReferenceRepo.asString(r, "relationship_type"));
            }
            g.parents.values().forEach(l -> l.sort(String::compareTo));
            g.children.values().forEach(l -> l.sort(String::compareTo));
            return g;
        }

        boolean isSplitEdge(String parent, String child) {
            return "SPLIT".equals(edgeRelationship.get(parent + "->" + child));
        }

        List<String> parents(String code) {
            return parents.getOrDefault(code, List.of());
        }

        List<String> children(String code) {
            return children.getOrDefault(code, List.of());
        }

        /** Deterministic rootward path: code, parent, grandparent, ... root. */
        List<String> parentPath(String code) {
            List<String> path = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            String cur = code;
            while (cur != null && seen.add(cur)) {
                path.add(cur);
                List<String> ps = parents.get(cur);
                cur = (ps == null || ps.isEmpty()) ? null : ps.get(0);
            }
            return path;
        }

        List<String> ancestors(String code) {
            List<String> path = parentPath(code);
            return path.size() <= 1 ? List.of() : path.subList(1, path.size());
        }

        List<String> descendants(String code) {
            Set<String> out = new LinkedHashSet<>();
            List<String> queue = new ArrayList<>(children.getOrDefault(code, List.of()));
            while (!queue.isEmpty()) {
                String c = queue.remove(0);
                if (out.add(c)) {
                    queue.addAll(children.getOrDefault(c, List.of()));
                }
            }
            return out.stream().sorted().toList();
        }

        List<String> edgesOnPaths(String a, String b, String lca) {
            Set<String> edges = new LinkedHashSet<>();
            collectEdges(a, lca, edges);
            collectEdges(b, lca, edges);
            return List.copyOf(edges);
        }

        private void collectEdges(String from, String stop, Set<String> edges) {
            String cur = from;
            Set<String> guard = new LinkedHashSet<>();
            while (cur != null && !cur.equals(stop) && guard.add(cur)) {
                List<String> ps = parents.get(cur);
                if (ps == null || ps.isEmpty()) {
                    break;
                }
                String p = ps.get(0);
                edges.add(p + "->" + cur);
                cur = p;
            }
        }

        List<String> aggregationKinds(List<String> edges) {
            return edges.stream().map(edgeAggregation::get).filter(Objects::nonNull)
                    .distinct().sorted().toList();
        }

        List<String> aggregationKindsWithin(Set<String> members) {
            List<String> kinds = new ArrayList<>();
            for (String child : members) {
                for (String parent : parents.getOrDefault(child, List.of())) {
                    if (members.contains(parent)) {
                        String k = edgeAggregation.get(parent + "->" + child);
                        if (k != null && !kinds.contains(k)) {
                            kinds.add(k);
                        }
                    }
                }
            }
            kinds.sort(String::compareTo);
            return kinds;
        }
    }
}
