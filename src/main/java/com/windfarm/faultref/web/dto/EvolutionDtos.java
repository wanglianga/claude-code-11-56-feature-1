package com.windfarm.faultref.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read models for concept-granularity evolution: hierarchy edges, bridge
 * matrix, unattributed alarms, work-order links, nearest-common-ancestor
 * comparisons and cross-version trend reports.
 */
public final class EvolutionDtos {

    private EvolutionDtos() {
    }

    public record HierarchyView(
            UUID id,
            String parentConceptCode,
            String parentConceptName,
            String childConceptCode,
            String childConceptName,
            String relationshipType,
            String aggregationKind,
            String manufacturerCode,
            String modelCode,
            String firmwareFrom,
            String firmwareTo,
            Integer firmwareFromSeq,
            Integer firmwareToSeq,
            String engineeringBasis,
            String status,
            Instant validFrom,
            Instant validTo,
            String approvedBy,
            String approvalRef,
            Instant approvedAt) {
    }

    public record BridgeView(
            UUID id,
            String matrixCode,
            String sourceConceptCode,
            String sourceConceptName,
            String targetConceptCode,
            String targetConceptName,
            String modelCode,
            String firmwareFrom,
            String firmwareTo,
            Integer firmwareFromSeq,
            Integer firmwareToSeq,
            BigDecimal weight,
            String engineeringBasis,
            String status,
            Instant validFrom,
            Instant validTo,
            String approvedBy,
            String approvalRef,
            Instant approvedAt) {
    }

    public record UnattributedView(
            UUID id,
            UUID alarmId,
            String alarmExternalId,
            String unitCode,
            String sourceSystemCode,
            String rawCode,
            String reasonCode,
            String rawFirmwareHint,
            String note,
            Instant occurredAt,
            Instant detectedAt,
            Instant resolvedAt,
            String resolutionNote) {
    }

    public record WorkOrderLinkView(
            UUID id,
            UUID workOrderId,
            String conceptCode,
            String conceptName,
            String linkType,
            String note,
            String createdBy,
            Instant createdAt) {
    }

    public record GranularityView(
            String conceptCode,
            String conceptName,
            List<HierarchyView> hierarchyEdges,
            List<BridgeView> bridgeRows,
            List<Map<String, Object>> undefinedChildWarnings) {
    }

    public record LcaResult(
            String conceptA,
            String conceptB,
            String comparisonKind,
            String commonAncestorCode,
            String commonAncestorName,
            int depthA,
            int depthB,
            List<String> pathA,
            List<String> pathB,
            List<String> aggregationKinds,
            boolean reversible,
            String explanation) {
    }

    public record TrendPoint(
            String period,
            long total,
            Map<String, Long> distribution,
            boolean fineDetailAvailable,
            String note) {
    }

    public record TrendMarker(
            String fromPeriod,
            String toPeriod,
            String marker,
            String message) {
    }

    public record TrendSeries(
            String seriesKey,
            String seriesName,
            String comparisonKind,
            String aggregationKind,
            boolean fineDetailMasked,
            List<String> memberConcepts,
            List<TrendPoint> points,
            List<TrendMarker> markers) {
    }

    public record ExclusionCount(String conceptCode, String conceptName, long alarms) {
    }

    public record TrendReport(
            String mode,
            String period,
            String graphSource,
            List<UUID> runIds,
            Instant from,
            Instant to,
            long alarmsConsidered,
            long alarmsExcluded,
            List<TrendSeries> series,
            List<UnattributedView> unattributedAlarms,
            List<ExclusionCount> undefinedSubcodes,
            List<ExclusionCount> unbridgedConcepts,
            String explanation) {
    }
}
