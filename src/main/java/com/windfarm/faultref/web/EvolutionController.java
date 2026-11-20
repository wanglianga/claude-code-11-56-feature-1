package com.windfarm.faultref.web;

import com.windfarm.faultref.service.EvolutionService;
import com.windfarm.faultref.web.dto.ApiRequests;
import com.windfarm.faultref.web.dto.EvolutionDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Concept-granularity evolution API: versioned parent/child concepts,
 * firmware applicability, reversible/irreversible aggregation, the
 * engineer-approved bridge matrix, pending-attribution alarms, reopened
 * work-order links, LCA comparisons and cross-version trend reports.
 */
@RestController
@RequestMapping("/api/evolution")
@Tag(name = "Concept granularity evolution",
        description = "Hierarchy and bridge-matrix continuity strategies across firmware code splits, "
                + "with explicit fine/common-ancestor/not-comparable trend markers")
public class EvolutionController {

    private final EvolutionService evolution;

    public EvolutionController(EvolutionService evolution) {
        this.evolution = evolution;
    }

    // ---- hierarchy ------------------------------------------------------
    @Operation(summary = "Propose a parent/child granularity edge (firmware-scoped, with engineering basis)")
    @PostMapping("/hierarchy")
    public Map<String, Object> createHierarchy(@Valid @RequestBody ApiRequests.HierarchyCreate req) {
        UUID id = evolution.createHierarchy(req);
        return Map.of("id", id, "status", "PROPOSED");
    }

    @Operation(summary = "Approve a hierarchy edge (admin only); only APPROVED edges feed statistics")
    @PostMapping("/hierarchy/{id}/approve")
    public EvolutionDtos.HierarchyView approveHierarchy(@PathVariable UUID id,
                                                        @Valid @RequestBody ApiRequests.HierarchyApprove req) {
        return evolution.approveHierarchy(id, req.approvedBy(), req.approvalRef());
    }

    @Operation(summary = "Retire a hierarchy edge")
    @PostMapping("/hierarchy/{id}/retire")
    public Map<String, Object> retireHierarchy(@PathVariable UUID id) {
        evolution.retireHierarchy(id);
        return Map.of("id", id, "status", "RETIRED");
    }

    @Operation(summary = "List hierarchy edges, optionally filtered by status")
    @GetMapping("/hierarchy")
    public List<EvolutionDtos.HierarchyView> hierarchy(@RequestParam(required = false) String status) {
        return evolution.listHierarchy(status);
    }

    @Operation(summary = "Granularity record for one concept: edges, bridges and undefined-subcode warnings")
    @GetMapping("/concepts/{code}/granularity")
    public EvolutionDtos.GranularityView granularity(@PathVariable String code) {
        return evolution.granularityOf(code);
    }

    @Operation(summary = "Compare two concepts through the hierarchy: fine level, common ancestor or not comparable")
    @GetMapping("/concepts/{codeA}/compare/{codeB}")
    public EvolutionDtos.LcaResult compare(@PathVariable String codeA, @PathVariable String codeB) {
        return evolution.compareConcepts(codeA, codeB);
    }

    // ---- bridge matrix --------------------------------------------------
    @Operation(summary = "Propose an engineer bridge row rolling one concept onto a comparable coarse concept")
    @PostMapping("/bridges")
    public Map<String, Object> createBridge(@Valid @RequestBody ApiRequests.BridgeCreate req) {
        UUID id = evolution.createBridge(req);
        return Map.of("id", id, "status", "PROPOSED");
    }

    @Operation(summary = "Approve a bridge row (admin only); only APPROVED rows feed bridged trends")
    @PostMapping("/bridges/{id}/approve")
    public EvolutionDtos.BridgeView approveBridge(@PathVariable UUID id,
                                                  @Valid @RequestBody ApiRequests.BridgeApprove req) {
        return evolution.approveBridge(id, req.approvedBy(), req.approvalRef());
    }

    @Operation(summary = "Retire a bridge row")
    @PostMapping("/bridges/{id}/retire")
    public Map<String, Object> retireBridge(@PathVariable UUID id) {
        evolution.retireBridge(id);
        return Map.of("id", id, "status", "RETIRED");
    }

    @Operation(summary = "List bridge rows, optionally filtered by status")
    @GetMapping("/bridges")
    public List<EvolutionDtos.BridgeView> bridges(@RequestParam(required = false) String status) {
        return evolution.listBridges(status);
    }

    // ---- unattributed alarms --------------------------------------------
    @Operation(summary = "Scan alarms and flag those without firmware evidence as pending attribution")
    @PostMapping("/unattributed/detect")
    public Map<String, Object> detect() {
        int flagged = evolution.detectMissingFirmwareEvidence();
        return Map.of("flagged", flagged);
    }

    @Operation(summary = "Manually flag one alarm as pending attribution")
    @PostMapping("/unattributed")
    public EvolutionDtos.UnattributedView flag(@RequestBody Map<String, String> body) {
        return evolution.flagUnattributed(body.get("alarmExternalId"), body.get("reasonCode"),
                body.get("rawFirmwareHint"), body.get("note"));
    }

    @Operation(summary = "List pending-attribution alarms (open only by default)")
    @GetMapping("/unattributed")
    public List<EvolutionDtos.UnattributedView> unattributed(
            @RequestParam(defaultValue = "true") boolean openOnly) {
        return evolution.listUnattributed(openOnly);
    }

    @Operation(summary = "Resolve a pending-attribution record after firmware evidence is established")
    @PostMapping("/unattributed/{id}/resolve")
    public EvolutionDtos.UnattributedView resolve(@PathVariable UUID id,
                                                  @Valid @RequestBody ApiRequests.UnattributedResolve req) {
        return evolution.resolveUnattributed(id, req.resolutionNote());
    }

    // ---- work order granularity links -----------------------------------
    @Operation(summary = "Link a reopened/historical work order to a new-granularity concept explanation",
            description = "The work order keeps its original diagnosis; this only attaches the granularity note.")
    @PostMapping("/work-orders/{workOrderId}/granularity-links")
    public EvolutionDtos.WorkOrderLinkView linkWorkOrder(@PathVariable UUID workOrderId,
                                                         @Valid @RequestBody ApiRequests.WorkOrderLinkCreate req,
                                                         Authentication auth) {
        return evolution.linkWorkOrder(workOrderId, req, auth == null ? "system" : auth.getName());
    }

    @Operation(summary = "List granularity links attached to a work order")
    @GetMapping("/work-orders/{workOrderId}/granularity-links")
    public List<EvolutionDtos.WorkOrderLinkView> workOrderLinks(@PathVariable UUID workOrderId) {
        return evolution.linksForWorkOrder(workOrderId);
    }

    // ---- trends ---------------------------------------------------------
    @Operation(summary = "Cross-version concept trend with explicit comparison markers",
            description = "HIERARCHY walks to the nearest common ancestor while keeping fine distributions; "
                    + "BRIDGE rolls codes onto one coarse concept via the approved matrix and masks fine detail. "
                    + "Every series states whether it is fine-grained, common-ancestor or not comparable.")
    @PostMapping("/trends")
    public EvolutionDtos.TrendReport trends(@Valid @RequestBody ApiRequests.TrendRequest req) {
        return evolution.trend(req);
    }
}
