package com.windfarm.faultref;

import com.windfarm.faultref.repo.EvolutionRepo;
import com.windfarm.faultref.repo.EvidenceRepo;
import com.windfarm.faultref.service.DiagnosisService;
import com.windfarm.faultref.service.EvidenceService;
import com.windfarm.faultref.service.EvolutionService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import com.windfarm.faultref.web.dto.EvolutionDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance scenarios for concept-granularity evolution across a firmware
 * split: interleaved old/new firmware, reopened historical work orders, a
 * subcode without an approved definition and shifting shares of the three
 * fine fault classes.
 */
@TestPropertySource(properties = "app.security.enabled=false")
class GranularityEvolutionTest extends IntegrationTestBase {

    @Autowired
    private TestFixtures fx;
    @Autowired
    private DiagnosisService diagnosis;
    @Autowired
    private EvolutionService evolution;
    @Autowired
    private EvolutionRepo evolutionRepo;
    @Autowired
    private EvidenceService evidence;
    @Autowired
    private EvidenceRepo evidenceRepo;

    private UUID runId;

    @BeforeEach
    void setUp() {
        fx.manufacturer("NW", "NordWind");
        fx.model("NW", "NW-8");
        fx.firmware("NW", "NW-8", "V1.0", 1);
        fx.firmware("NW", "NW-8", "V3.0", 2);
        fx.unit("A01", "NW", "NW-8");
        fx.unit("A02", "NW", "NW-8");
        // A01 crosses the split; A02 never reports an install window.
        fx.install("A01", "V1.0", "2025-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        fx.install("A01", "V3.0", "2026-02-01T00:00:00Z", null);

        fx.controller("NW_CTRL");
        fx.concept("HYD_ABNORMAL", "Hydraulic pressure abnormal", "HYDRAULIC", "HIGH", "umbrella concept");
        fx.concept("HYD_LOW", "Low pressure (coarse)", "HYDRAULIC", "HIGH", "old coarse code");
        fx.concept("HYD_LOW_FINE", "Low pressure (fine)", "HYDRAULIC", "HIGH", "split fine code");
        fx.concept("HYD_FLUCT", "Pressure fluctuation", "HYDRAULIC", "MEDIUM", "new fine code");
        fx.concept("HYD_SENSOR", "Sensor failure", "HYDRAULIC", "HIGH", "defined fine code without a bridge");
        fx.concept("HYD_X", "Undefined subcode", "HYDRAULIC", "HIGH", "no approved definition yet");
        fx.concept("HYD_TEMP", "Oil temperature high", "HYDRAULIC", "MEDIUM", "unrelated concept");

        UUID oldCode = fx.sourceCode("NW_CTRL", "OLD-1");
        UUID newLow = fx.sourceCode("NW_CTRL", "NEW-1");
        UUID newFluct = fx.sourceCode("NW_CTRL", "NEW-2");
        UUID newX = fx.sourceCode("NW_CTRL", "NEW-3");
        UUID newSensor = fx.sourceCode("NW_CTRL", "NEW-4");
        fx.assertion(oldCode, "HYD_LOW", 1, null, null, null, null, "MOC-OLD");
        fx.assertion(newLow, "HYD_LOW_FINE", 1, "NW", "NW-8", "V3.0", null, "MOC-F1");
        fx.assertion(newFluct, "HYD_FLUCT", 1, "NW", "NW-8", "V3.0", null, "MOC-F2");
        fx.assertion(newX, "HYD_X", 1, "NW", "NW-8", "V3.0", null, "MOC-F3");
        fx.assertion(newSensor, "HYD_SENSOR", 1, "NW", "NW-8", "V3.0", null, "MOC-F4");

        // Approved hierarchy: reversible low-pressure line, irreversible new
        // fine codes, old coarse concept kept via REFINE.
        fx.approveHierarchy("HYD_ABNORMAL", "HYD_LOW", "REFINE", "REVERSIBLE",
                "NW", "NW-8", "old coarse retained under umbrella", "MOC-H1");
        fx.approveHierarchy("HYD_ABNORMAL", "HYD_LOW_FINE", "SPLIT", "REVERSIBLE",
                "NW", "NW-8", "fine low pressure comparable to old coarse", "MOC-H2");
        fx.approveHierarchy("HYD_ABNORMAL", "HYD_FLUCT", "SPLIT", "IRREVERSIBLE",
                "NW", "NW-8", "fluctuation not reconstructable from old firmware", "MOC-H3");
        // Defined fine code, but deliberately no bridge row.
        fx.approveHierarchy("HYD_ABNORMAL", "HYD_SENSOR", "SPLIT", "IRREVERSIBLE",
                "NW", "NW-8", "sensor failure split, excluded only from bridged reports", "MOC-H4");
        // Deliberately left PROPOSED: HYD_X has no approved engineering definition.
        fx.hierarchy("HYD_ABNORMAL", "HYD_X", "SPLIT", "IRREVERSIBLE",
                "NW", "NW-8", "pending engineering basis");

        // Engineer-approved bridge matrix onto the umbrella; HYD_X has no bridge.
        fx.approveBridge("M1", "HYD_LOW", "HYD_ABNORMAL", "NW", "NW-8", 1.0, "coarse roll-up", "MOC-B1");
        fx.approveBridge("M1", "HYD_LOW_FINE", "HYD_ABNORMAL", "NW", "NW-8", 1.0, "fine roll-up", "MOC-B2");
        fx.approveBridge("M1", "HYD_FLUCT", "HYD_ABNORMAL", "NW", "NW-8", 1.0, "fine roll-up", "MOC-B3");

        // Old firmware window: coarse code only.
        fx.alarm("A-OLD-1", "A01", "NW_CTRL", "OLD-1", "2026-01-10T00:00:00Z",
                "2026-01-10T00:00:10Z", "REALTIME");
        fx.alarm("A-OLD-2", "A01", "NW_CTRL", "OLD-1", "2026-01-20T00:00:00Z",
                "2026-01-20T00:00:10Z", "REALTIME");
        // New firmware window: fine codes with a visibly changing mix.
        fx.alarm("A-NEW-LOW-1", "A01", "NW_CTRL", "NEW-1", "2026-02-02T00:00:00Z",
                "2026-02-02T00:00:10Z", "REALTIME");
        fx.alarm("A-NEW-LOW-2", "A01", "NW_CTRL", "NEW-1", "2026-02-09T00:00:00Z",
                "2026-02-09T00:00:10Z", "REALTIME");
        fx.alarm("A-NEW-FLUCT-1", "A01", "NW_CTRL", "NEW-2", "2026-02-12T00:00:00Z",
                "2026-02-12T00:00:10Z", "REALTIME");
        fx.alarm("A-NEW-SENSOR-1", "A01", "NW_CTRL", "NEW-4", "2026-02-16T00:00:00Z",
                "2026-02-16T00:00:10Z", "REALTIME");
        fx.alarm("A-NEW-X-1", "A01", "NW_CTRL", "NEW-3", "2026-02-18T00:00:00Z",
                "2026-02-18T00:00:10Z", "REALTIME");
        // A02 has no firmware evidence: pending attribution only.
        fx.alarm("A-NOFW-1", "A02", "NW_CTRL", "NEW-1", "2026-02-15T00:00:00Z",
                "2026-02-15T00:00:09Z", "REALTIME");
        evolution.detectMissingFirmwareEvidence();

        DiagnosisDtos.Run run = diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST",
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-03-01T00:00:00Z"), null, false, null, null, null), "tester");
        runId = run.id();
    }

    @Test
    void siblingSplitCodesCompareAtFineLevel() {
        EvolutionDtos.LcaResult r = evolution.compareConcepts("HYD_LOW_FINE", "HYD_FLUCT");
        assertThat(r.comparisonKind()).isEqualTo("FINE_GRAINED");
        assertThat(r.commonAncestorCode()).isEqualTo("HYD_ABNORMAL");
        assertThat(r.aggregationKinds()).contains("REVERSIBLE", "IRREVERSIBLE");
        assertThat(r.reversible()).isFalse();
    }

    @Test
    void oldCoarseAgainstNewFineIsCommonAncestorNotFine() {
        EvolutionDtos.LcaResult r = evolution.compareConcepts("HYD_LOW", "HYD_FLUCT");
        assertThat(r.comparisonKind()).isEqualTo("COMMON_ANCESTOR");
        assertThat(r.commonAncestorCode()).isEqualTo("HYD_ABNORMAL");
    }

    @Test
    void disjointConceptsAreNotComparable() {
        EvolutionDtos.LcaResult r = evolution.compareConcepts("HYD_TEMP", "HYD_FLUCT");
        assertThat(r.comparisonKind()).isEqualTo("NOT_COMPARABLE");
        assertThat(r.commonAncestorCode()).isNull();
    }

    @Test
    void hierarchyTrendKeepsFineDistributionAndFlagsExclusions() {
        EvolutionDtos.TrendReport report = evolution.trend(new ApiRequests.TrendRequest(
                List.of(runId), null, null, "MONTH", "HIERARCHY", "HYD_ABNORMAL"));

        assertThat(report.series()).hasSize(1);
        EvolutionDtos.TrendSeries series = report.series().get(0);
        assertThat(series.comparisonKind()).isEqualTo("COMMON_ANCESTOR");
        assertThat(series.fineDetailMasked()).isFalse();
        assertThat(series.points()).hasSize(2);
        // January: only the old coarse code. February: the fine split is preserved.
        Map<String, Long> jan = series.points().get(0).distribution();
        Map<String, Long> feb = series.points().get(1).distribution();
        assertThat(jan).containsEntry("HYD_LOW", 2L);
        assertThat(feb).containsEntry("HYD_LOW_FINE", 2L).containsEntry("HYD_FLUCT", 1L)
                .containsEntry("HYD_SENSOR", 1L);
        assertThat(feb).doesNotContainKey("HYD_X");

        List<String> markers = series.markers().stream().map(EvolutionDtos.TrendMarker::marker).toList();
        assertThat(markers).contains("FINE_DISTRIBUTION_SHIFT", "IRREVERSIBLE_AGGREGATION_PRESENT");

        // The undefined subcode and the firmware-evidence-missing alarm never enter stats.
        assertThat(report.undefinedSubcodes()).extracting(EvolutionDtos.ExclusionCount::conceptCode)
                .containsExactly("HYD_X");
        assertThat(report.unattributedAlarms()).extracting(EvolutionDtos.UnattributedView::alarmExternalId)
                .containsExactly("A-NOFW-1");
        assertThat(report.alarmsExcluded()).isEqualTo(2);
        assertThat(report.alarmsConsidered()).isEqualTo(8);
    }

    @Test
    void bridgeTrendIsStableButMasksFineDetailAndReportsUnbridged() {
        EvolutionDtos.TrendReport report = evolution.trend(new ApiRequests.TrendRequest(
                List.of(runId), null, null, "MONTH", "BRIDGE", null));

        EvolutionDtos.TrendSeries umbrella = report.series().stream()
                .filter(s -> s.seriesKey().equals("HYD_ABNORMAL")).findFirst().orElseThrow();
        assertThat(umbrella.fineDetailMasked()).isTrue();
        assertThat(umbrella.points()).extracting(TrendPointTotal::new)
                .containsExactly(new TrendPointTotal("2026-01", 2), new TrendPointTotal("2026-02", 3));
        // Every bridged point reports a single coarse bucket, never the fine mix.
        umbrella.points().forEach(p -> assertThat(p.distribution()).hasSize(1));
        assertThat(umbrella.markers().stream().map(EvolutionDtos.TrendMarker::marker))
                .contains("FINE_DETAIL_MASKED", "CONTINUOUS_BRIDGED");

        // HYD_SENSOR is defined but has no approved bridge: excluded and listed rather than guessed.
        assertThat(report.unbridgedConcepts()).extracting(EvolutionDtos.ExclusionCount::conceptCode)
                .containsExactly("HYD_SENSOR");
        assertThat(report.series()).filteredOn(s -> s.seriesKey().equals("UNBRIDGED")).hasSize(1);
        // The undefined subcode is reported separately, never as an ordinary unbridged concept.
        assertThat(report.undefinedSubcodes()).extracting(EvolutionDtos.ExclusionCount::conceptCode)
                .containsExactly("HYD_X");
        // Every in-window alarm is accounted for exactly once: 5 bridged, 3 excluded
        // (one undefined, one unattributed, one unbridged). No double counting.
        assertThat(report.alarmsExcluded()).isEqualTo(3);
        assertThat(report.alarmsConsidered()).isEqualTo(8);
    }

    @Test
    void unattributedAlarmStaysPendingUntilResolved() {
        List<EvolutionDtos.UnattributedView> open = evolution.listUnattributed(true);
        assertThat(open).extracting(EvolutionDtos.UnattributedView::alarmExternalId)
                .containsExactly("A-NOFW-1");
        UUID id = open.get(0).id();
        evolution.resolveUnattributed(id, "Install window recovered from maintenance log");
        assertThat(evolution.listUnattributed(true)).isEmpty();
        assertThat(evolution.listUnattributed(false).get(0).resolvedAt()).isNotNull();
    }

    @Test
    void reopenedWorkOrderKeepsOriginalDiagnosisAndGainsGranularityLink() {
        Map<String, Object> wo = evidence.ingestWorkOrder(new EvidenceService.NewWorkOrder(
                "WO-REOPEN-1", "A01", "远海运维", "液压压力低", "液压压力低",
                Instant.parse("2026-01-12T00:00:00Z"), Instant.parse("2026-01-13T00:00:00Z"),
                "REOPENED", "原始诊断为粗类液压压力低", Map.of()));
        UUID workOrderId = (UUID) wo.get("id");

        EvolutionDtos.WorkOrderLinkView link = evolution.linkWorkOrder(workOrderId,
                new ApiRequests.WorkOrderLinkCreate("HYD_LOW_FINE", "REOPENED_GRANULARITY_NOTE",
                        "拆分发布后重开，仅关联新细类说明，不改写原诊断"), "engineer");

        assertThat(link.conceptCode()).isEqualTo("HYD_LOW_FINE");
        List<EvolutionDtos.WorkOrderLinkView> links = evolution.linksForWorkOrder(workOrderId);
        assertThat(links).hasSize(1);
        // The original term remains the coarse diagnosis; the link is additive.
        Map<String, Object> stored = evidenceRepo.workOrderById(workOrderId).orElseThrow();
        assertThat(stored.get("reported_term")).isEqualTo("液压压力低");
    }

    @Test
    void releaseFreezesOnlyApprovedEvolutionRows() {
        fx.part("P-1", "PUMP");
        fx.catalogVersion("CAT-1", "DRAFT", "2026-01-01T00:00:00Z");
        fx.entry("CAT-1", "P-1");
        fx.disposition("HYD_LOW", "CAT-1", "D-1", "INSPECT", null, 1, null, false, 1,
                "2026-01-01T00:00:00Z");
        jdbc.getJdbcTemplate().update(
                "update catalog_version set status='PUBLISHED', published_at=now(), approved_by='approver' "
                        + "where version_label='CAT-1'");
        fx.inventory("INV-1", "2026-01-01T00:00:00Z", "CAT-1", List.of(fx.line("P-1", "W1", 3)));
        fx.publishRelease("REL-EVO", "2026-02-01T00:00:00Z", "CAT-1", "INV-1");

        UUID releaseId = jdbc.queryForObject(
                "select id from release where version_label = :l", Map.of("l", "REL-EVO"), UUID.class);
        // Four approved hierarchy rows; the PROPOSED HYD_X edge is not frozen.
        assertThat(evolutionRepo.releaseHierarchy(releaseId)).hasSize(4);
        assertThat(evolutionRepo.releaseBridges(releaseId)).hasSize(3);
        assertThat(evolutionRepo.releaseHierarchy(releaseId))
                .noneMatch(r -> "HYD_X".equals(r.get("child_concept_code")));
    }

    private record TrendPointTotal(String period, long total) {
        TrendPointTotal(EvolutionDtos.TrendPoint p) {
            this(p.period(), p.total());
        }
    }
}
