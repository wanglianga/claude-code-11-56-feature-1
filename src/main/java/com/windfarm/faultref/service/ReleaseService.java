package com.windfarm.faultref.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.windfarm.faultref.error.ApiException;
import com.windfarm.faultref.repo.CatalogRepo;
import com.windfarm.faultref.repo.EvolutionRepo;
import com.windfarm.faultref.repo.ReferenceRepo;
import com.windfarm.faultref.repo.ReleaseRepo;
import com.windfarm.faultref.repo.Sql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * History model B: release snapshots.
 *
 * Publishing freezes the approved code table, terminology, concept
 * definitions, firmware timeline, catalog, stock and dispositions into
 * immutable release_* rows. Diagnosis in SNAPSHOT mode reads only those rows,
 * so historical replay is direct and later catalog publishes cannot perturb it.
 *
 * Release content is built from the assertion table but contains a *resolved,
 * de-duplicated* mapping set: for each (source, scope) the winning specificity
 * tier is frozen. Preserved conflicts that survive resolution are frozen with
 * all of their competing concepts so a replay can still report them.
 */
@Service
public class ReleaseService {

    private final ReleaseRepo releases;
    private final ReferenceRepo reference;
    private final CatalogRepo catalog;
    private final EvolutionRepo evolution;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReleaseService(ReleaseRepo releases, ReferenceRepo reference, CatalogRepo catalog,
                          EvolutionRepo evolution) {
        this.releases = releases;
        this.reference = reference;
        this.catalog = catalog;
        this.evolution = evolution;
    }

    @Transactional
    public Map<String, Object> createDraft(String label, Instant effectiveFrom, String catalogVersionLabel,
                                           String inventorySnapshotLabel, String createdBy, String notes) {
        if (releases.releaseByLabel(label).isPresent()) {
            throw ApiException.conflict("RELEASE_EXISTS", "Release " + label + " already exists");
        }
        if (catalogVersionLabel == null) {
            throw ApiException.badRequest("CATALOG_REQUIRED", "catalogVersionLabel is required");
        }
        Map<String, Object> cv = ReferenceRepo.require(catalog.catalogVersionByLabel(catalogVersionLabel),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + catalogVersionLabel);
        if (!"PUBLISHED".equals(ReferenceRepo.asString(cv, "status"))) {
            throw ApiException.conflict("CATALOG_NOT_PUBLISHED",
                    "Catalog version " + catalogVersionLabel + " must be PUBLISHED before it can be released");
        }
        if (inventorySnapshotLabel != null) {
            ReferenceRepo.require(catalog.inventorySnapshotByLabel(inventorySnapshotLabel),
                    "INVENTORY_SNAPSHOT_NOT_FOUND", "Unknown inventory snapshot " + inventorySnapshotLabel);
        }
        UUID id = releases.insertRelease(new MapSqlParameterSource()
                .addValue("label", label)
                .addValue("status", "DRAFT")
                .addValue("effectiveFrom", Sql.odt(effectiveFrom == null ? Instant.now() : effectiveFrom))
                .addValue("catalogLabel", catalogVersionLabel)
                .addValue("inventoryLabel", inventorySnapshotLabel)
                .addValue("hash", null)
                .addValue("approvedBy", null)
                .addValue("approvalRef", null)
                .addValue("publishedAt", null)
                .addValue("createdBy", createdBy == null ? "system" : createdBy)
                .addValue("notes", notes));
        return Map.of("id", id, "versionLabel", label, "status", "DRAFT");
    }

    /**
     * Publish: validates approvals exist for every included assertion and
     * disposition, freezes content, and computes a deterministic content hash.
     */
    @Transactional
    public Map<String, Object> publish(String label, String approvedBy, String approvalRef) {
        Map<String, Object> r = ReferenceRepo.require(releases.releaseByLabel(label),
                "RELEASE_NOT_FOUND", "Unknown release " + label);
        UUID releaseId = ReferenceRepo.asUuid(r, "id");
        if (!"DRAFT".equals(ReferenceRepo.asString(r, "status"))) {
            throw ApiException.conflict("RELEASE_NOT_DRAFT",
                    "Release " + label + " is " + ReferenceRepo.asString(r, "status") + ", not DRAFT");
        }
        Instant effectiveFrom = ReferenceRepo.asInstant(r, "effective_from");
        String catalogLabel = ReferenceRepo.asString(r, "catalog_version_label");
        String inventoryLabel = ReferenceRepo.asString(r, "inventory_snapshot_label");

        // ---- freeze source codes --------------------------------------
        List<Map<String, Object>> sourceCodes = reference.listSourceCodes();
        List<SqlParameterSource> codeBatch = new ArrayList<>();
        for (Map<String, Object> sc : sourceCodes) {
            codeBatch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("systemCode", ReferenceRepo.asString(sc, "source_system_code"))
                    .addValue("code", ReferenceRepo.asString(sc, "code"))
                    .addValue("rawText", ReferenceRepo.asString(sc, "raw_text"))
                    .addValue("description", ReferenceRepo.asString(sc, "description")));
        }
        if (!codeBatch.isEmpty()) {
            releases.insertSourceCodes(codeBatch.toArray(new SqlParameterSource[0]));
        }

        // ---- freeze terms ---------------------------------------------
        List<Map<String, Object>> terms = reference.listTerms();
        List<SqlParameterSource> termBatch = new ArrayList<>();
        for (Map<String, Object> t : terms) {
            termBatch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("systemCode", ReferenceRepo.asString(t, "source_system_code"))
                    .addValue("vendor", ReferenceRepo.asString(t, "vendor"))
                    .addValue("termText", ReferenceRepo.asString(t, "term_text"))
                    .addValue("attachmentRef", ReferenceRepo.asString(t, "attachment_ref"))
                    .addValue("notes", ReferenceRepo.asString(t, "notes")));
        }
        if (!termBatch.isEmpty()) {
            releases.insertTerms(termBatch.toArray(new SqlParameterSource[0]));
        }

        // ---- freeze concept definitions --------------------------------
        List<Map<String, Object>> concepts = reference.listConcepts();
        List<SqlParameterSource> conceptBatch = new ArrayList<>();
        for (Map<String, Object> c : concepts) {
            conceptBatch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("conceptCode", ReferenceRepo.asString(c, "code"))
                    .addValue("name", ReferenceRepo.asString(c, "name"))
                    .addValue("category", ReferenceRepo.asString(c, "category"))
                    .addValue("severity", ReferenceRepo.asString(c, "severity"))
                    .addValue("definitionVersion", ReferenceRepo.asInt(c, "current_definition_version"))
                    .addValue("definitionText", ReferenceRepo.asString(c, "definition"))
                    .addValue("approvedBy", ReferenceRepo.asString(c, "approved_by"))
                    .addValue("approvalRef", ReferenceRepo.asString(c, "approval_ref"))
                    .addValue("approvedAt", Sql.odt(ReferenceRepo.asInstant(c, "approved_at"))));
        }
        if (!conceptBatch.isEmpty()) {
            releases.insertConcepts(conceptBatch.toArray(new SqlParameterSource[0]));
        }

        // ---- freeze firmware timeline ---------------------------------
        List<SqlParameterSource> fwBatch = new ArrayList<>();
        for (Map<String, Object> f : reference.listFirmware()) {
            fwBatch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("manufacturerCode", ReferenceRepo.asString(f, "manufacturer_code"))
                    .addValue("modelCode", ReferenceRepo.asString(f, "model_code"))
                    .addValue("version", ReferenceRepo.asString(f, "version"))
                    .addValue("sequence", ReferenceRepo.asInt(f, "sequence"))
                    .addValue("releasedOn", f.get("released_on") == null ? null
                            : java.sql.Date.valueOf(f.get("released_on").toString())));
        }
        if (!fwBatch.isEmpty()) {
            releases.insertFirmware(fwBatch.toArray(new SqlParameterSource[0]));
        }

        // ---- freeze codes + terms mapping entries ---------------------
        int codeEntries = freezeCodeMappings(releaseId, effectiveFrom);
        int termEntries = freezeTermMappings(releaseId, effectiveFrom);

        // ---- freeze catalog, stock, dispositions -----------------------
        freezeCatalog(releaseId, catalogLabel, inventoryLabel, effectiveFrom);

        // ---- freeze adjudications -------------------------------------
        freezeAdjudications(releaseId);

        // ---- freeze concept granularity evolution ---------------------
        freezeGranularity(releaseId);

        String hash = contentHash(releaseId);
        releases.publish(releaseId, hash, approvedBy, approvalRef);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", releaseId);
        out.put("versionLabel", label);
        out.put("status", "PUBLISHED");
        out.put("contentHash", hash);
        out.put("codeEntries", codeEntries);
        out.put("termEntries", termEntries);
        out.put("approvedBy", approvedBy);
        out.put("approvalRef", approvalRef);
        return out;
    }

    /**
     * Freeze each ACTIVE assertion that carried an approval. Entries are keyed by
     * (source, scope, firmware range) and deliberately keep competing concepts:
     * a release must not silently pick a winner among a preserved conflict.
     */
    private int freezeCodeMappings(UUID releaseId, Instant effectiveFrom) {
        List<Map<String, Object>> rows = releases.activeAssertionsForFreeze();
        List<SqlParameterSource> batch = new ArrayList<>();
        for (Map<String, Object> a : rows) {
            if (ReferenceRepo.asString(a, "source_system_kind") == null
                    || !"CONTROLLER".equals(ReferenceRepo.asString(a, "source_system_kind"))) {
                continue;
            }
            if (ReferenceRepo.asString(a, "approval_ref") == null) {
                throw ApiException.unprocessable("ASSERTION_NOT_APPROVED",
                        "Assertion " + ReferenceRepo.asString(a, "id")
                                + " has no approval_ref and cannot be frozen into a release");
            }
            batch.add(codeEntryParams(releaseId, effectiveFrom, a));
        }
        if (!batch.isEmpty()) {
            releases.insertCodeEntries(batch.toArray(new SqlParameterSource[0]));
        }
        return batch.size();
    }

    private int freezeTermMappings(UUID releaseId, Instant effectiveFrom) {
        List<Map<String, Object>> rows = releases.activeAssertionsForFreeze();
        List<SqlParameterSource> batch = new ArrayList<>();
        for (Map<String, Object> a : rows) {
            if (ReferenceRepo.asString(a, "term_id") == null) {
                continue;
            }
            if (ReferenceRepo.asString(a, "approval_ref") == null) {
                throw ApiException.unprocessable("ASSERTION_NOT_APPROVED",
                        "Term assertion " + ReferenceRepo.asString(a, "id")
                                + " has no approval_ref and cannot be frozen into a release");
            }
            batch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("vendor", ReferenceRepo.asString(a, "vendor"))
                    .addValue("termText", ReferenceRepo.asString(a, "term_text"))
                    .addValue("manufacturerCode", ReferenceRepo.asString(a, "manufacturer_code"))
                    .addValue("modelCode", ReferenceRepo.asString(a, "model_code"))
                    .addValue("firmwareFrom", ReferenceRepo.asString(a, "firmware_from"))
                    .addValue("firmwareTo", ReferenceRepo.asString(a, "firmware_to"))
                    .addValue("firmwareFromSeq", ReferenceRepo.asInt(a, "firmware_from_seq"))
                    .addValue("firmwareToSeq", ReferenceRepo.asInt(a, "firmware_to_seq"))
                    .addValue("qualifiers", Sql.jsonb(Sql.readMap(a.get("qualifiers"))))
                    .addValue("conceptCode", ReferenceRepo.asString(a, "concept_code"))
                    .addValue("conceptName", ReferenceRepo.asString(a, "concept_name"))
                    .addValue("conceptCategory", ReferenceRepo.asString(a, "category"))
                    .addValue("conceptSeverity", ReferenceRepo.asString(a, "severity"))
                    .addValue("definitionVersion", ReferenceRepo.asInt(a, "def_version"))
                    .addValue("definitionText", ReferenceRepo.asString(a, "definition"))
                    .addValue("confidence", Sql.decimal(a.get("confidence")))
                    .addValue("assertionId", ReferenceRepo.asUuid(a, "id"))
                    .addValue("validFrom", Sql.odt(ReferenceRepo.asInstant(a, "valid_from")))
                    .addValue("validTo", Sql.odt(ReferenceRepo.asInstant(a, "valid_to")))
                    .addValue("approvalRef", ReferenceRepo.asString(a, "approval_ref"))
                    .addValue("approvedBy", ReferenceRepo.asString(a, "approved_by"))
                    .addValue("approvedAt", Sql.odt(ReferenceRepo.asInstant(a, "approved_at"))));
        }
        if (!batch.isEmpty()) {
            releases.insertTermEntries(batch.toArray(new SqlParameterSource[0]));
        }
        return batch.size();
    }

    private MapSqlParameterSource codeEntryParams(UUID releaseId, Instant effectiveFrom, Map<String, Object> a) {
        return new MapSqlParameterSource()
                .addValue("releaseId", releaseId)
                .addValue("systemCode", ReferenceRepo.asString(a, "source_system_code"))
                .addValue("systemKind", ReferenceRepo.asString(a, "source_system_kind"))
                .addValue("sourceCode", ReferenceRepo.asString(a, "source_code"))
                .addValue("rawText", ReferenceRepo.asString(a, "raw_text"))
                .addValue("manufacturerCode", ReferenceRepo.asString(a, "manufacturer_code"))
                .addValue("modelCode", ReferenceRepo.asString(a, "model_code"))
                .addValue("firmwareFrom", ReferenceRepo.asString(a, "firmware_from"))
                .addValue("firmwareTo", ReferenceRepo.asString(a, "firmware_to"))
                .addValue("firmwareFromSeq", ReferenceRepo.asInt(a, "firmware_from_seq"))
                .addValue("firmwareToSeq", ReferenceRepo.asInt(a, "firmware_to_seq"))
                .addValue("qualifiers", Sql.jsonb(Sql.readMap(a.get("qualifiers"))))
                .addValue("conceptCode", ReferenceRepo.asString(a, "concept_code"))
                .addValue("conceptName", ReferenceRepo.asString(a, "concept_name"))
                .addValue("conceptCategory", ReferenceRepo.asString(a, "category"))
                .addValue("conceptSeverity", ReferenceRepo.asString(a, "severity"))
                .addValue("definitionVersion", ReferenceRepo.asInt(a, "def_version"))
                .addValue("definitionText", ReferenceRepo.asString(a, "definition"))
                .addValue("confidence", Sql.decimal(a.get("confidence")))
                .addValue("assertionId", ReferenceRepo.asUuid(a, "id"))
                .addValue("validFrom", Sql.odt(ReferenceRepo.asInstant(a, "valid_from")))
                .addValue("validTo", Sql.odt(ReferenceRepo.asInstant(a, "valid_to")))
                .addValue("approvalRef", ReferenceRepo.asString(a, "approval_ref"))
                .addValue("approvedBy", ReferenceRepo.asString(a, "approved_by"))
                .addValue("approvedAt", Sql.odt(ReferenceRepo.asInstant(a, "approved_at")));
    }

    private void freezeCatalog(UUID releaseId, String catalogLabel, String inventoryLabel, Instant effectiveFrom) {
        Map<String, Object> cv = ReferenceRepo.require(catalog.catalogVersionByLabel(catalogLabel),
                "CATALOG_VERSION_NOT_FOUND", "Unknown catalog version " + catalogLabel);
        UUID cvId = ReferenceRepo.asUuid(cv, "id");

        // parts + compatibility in this catalog version
        List<Map<String, Object>> parts = releases.partsInCatalog(cvId);
        Map<String, UUID> partRowIds = new LinkedHashMap<>();
        List<SqlParameterSource> partBatch = new ArrayList<>();
        for (Map<String, Object> p : parts) {
            partBatch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("partNumber", ReferenceRepo.asString(p, "part_number"))
                    .addValue("name", ReferenceRepo.asString(p, "name"))
                    .addValue("category", ReferenceRepo.asString(p, "category"))
                    .addValue("description", ReferenceRepo.asString(p, "description"))
                    .addValue("attributes", Sql.jsonb(Sql.readMap(p.get("attributes"))))
                    .addValue("catalogLabel", catalogLabel));
        }
        if (!partBatch.isEmpty()) {
            releases.insertParts(partBatch.toArray(new SqlParameterSource[0]));
        }
        // Map part_number -> release_part id
        for (Map<String, Object> rp : releases.parts(releaseId)) {
            partRowIds.put(ReferenceRepo.asString(rp, "part_number"), ReferenceRepo.asUuid(rp, "id"));
        }
        List<SqlParameterSource> compatBatch = new ArrayList<>();
        for (Map<String, Object> c : releases.compatInCatalog(cvId)) {
            UUID partRow = partRowIds.get(ReferenceRepo.asString(c, "part_number"));
            if (partRow == null) {
                continue;
            }
            compatBatch.add(new MapSqlParameterSource()
                    .addValue("releasePartId", partRow)
                    .addValue("manufacturerCode", ReferenceRepo.asString(c, "manufacturer_code"))
                    .addValue("modelCode", ReferenceRepo.asString(c, "model_code"))
                    .addValue("firmwareFrom", ReferenceRepo.asString(c, "firmware_from"))
                    .addValue("firmwareTo", ReferenceRepo.asString(c, "firmware_to"))
                    .addValue("firmwareFromSeq", ReferenceRepo.asInt(c, "firmware_from_seq"))
                    .addValue("firmwareToSeq", ReferenceRepo.asInt(c, "firmware_to_seq")));
        }
        if (!compatBatch.isEmpty()) {
            releases.insertCompat(compatBatch.toArray(new SqlParameterSource[0]));
        }

        // inventory from the named snapshot (stock decides disposition availability)
        if (inventoryLabel != null) {
            UUID invId = ReferenceRepo.asUuid(ReferenceRepo.require(catalog.inventorySnapshotByLabel(inventoryLabel),
                    "INVENTORY_SNAPSHOT_NOT_FOUND", "Unknown inventory snapshot " + inventoryLabel), "id");
            List<SqlParameterSource> invBatch = new ArrayList<>();
            for (Map<String, Object> line : catalog.inventoryLevels(invId)) {
                invBatch.add(new MapSqlParameterSource()
                        .addValue("releaseId", releaseId)
                        .addValue("partNumber", ReferenceRepo.asString(line, "part_number"))
                        .addValue("warehouse", ReferenceRepo.asString(line, "warehouse"))
                        .addValue("qty", ReferenceRepo.asInt(line, "qty_available")));
            }
            if (!invBatch.isEmpty()) {
                releases.insertInventory(invBatch.toArray(new SqlParameterSource[0]));
            }
        }

        // dispositions for this catalog version
        List<SqlParameterSource> dispBatch = new ArrayList<>();
        for (Map<String, Object> d : catalog.dispositionsForCatalog(cvId)) {
            UUID partId = CatalogRepo.uuid(d, "part_id");
            String partNumber = partId == null ? null
                    : catalog.partByNumber(ReferenceRepo.asString(d, "part_number")).map(x -> ReferenceRepo.asString(x, "part_number")).orElse(null);
            dispBatch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("conceptCode", ReferenceRepo.asString(d, "concept_code"))
                    .addValue("code", ReferenceRepo.asString(d, "code"))
                    .addValue("action", ReferenceRepo.asString(d, "action"))
                    .addValue("description", ReferenceRepo.asString(d, "description"))
                    .addValue("partNumber", ReferenceRepo.asString(d, "part_number"))
                    .addValue("quantity", ReferenceRepo.asInt(d, "quantity"))
                    .addValue("exclusiveGroup", ReferenceRepo.asString(d, "exclusive_group"))
                    .addValue("requiresStock", CatalogRepo.bool(d, "requires_stock"))
                    .addValue("minStock", ReferenceRepo.asInt(d, "min_stock"))
                    .addValue("catalogLabel", catalogLabel)
                    .addValue("validFrom", Sql.odt(CatalogRepo.instant(d, "valid_from")))
                    .addValue("validTo", Sql.odt(CatalogRepo.instant(d, "valid_to")))
                    .addValue("approvedBy", ReferenceRepo.asString(d, "approved_by"))
                    .addValue("approvalRef", ReferenceRepo.asString(d, "approval_ref")));
        }
        if (!dispBatch.isEmpty()) {
            releases.insertDispositions(dispBatch.toArray(new SqlParameterSource[0]));
        }
    }

    private void freezeAdjudications(UUID releaseId) {
        List<SqlParameterSource> batch = new ArrayList<>();
        for (Map<String, Object> adj : releases.allAdjudications()) {
            batch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("sourceKind", ReferenceRepo.asString(adj, "source_kind"))
                    .addValue("systemCode", ReferenceRepo.asString(adj, "source_system_code"))
                    .addValue("sourceCode", ReferenceRepo.asString(adj, "source_code"))
                    .addValue("vendor", ReferenceRepo.asString(adj, "vendor"))
                    .addValue("termText", ReferenceRepo.asString(adj, "term_text"))
                    .addValue("conceptACode", ReferenceRepo.asString(adj, "concept_a_code"))
                    .addValue("conceptBCode", ReferenceRepo.asString(adj, "concept_b_code"))
                    .addValue("chosenConceptCode", ReferenceRepo.asString(adj, "chosen_concept_code"))
                    .addValue("modelCode", ReferenceRepo.asString(adj, "model_code"))
                    .addValue("validFrom", Sql.odt(ReferenceRepo.asInstant(adj, "valid_from")))
                    .addValue("validTo", Sql.odt(ReferenceRepo.asInstant(adj, "valid_to")))
                    .addValue("decision", ReferenceRepo.asString(adj, "decision"))
                    .addValue("adjudicatedBy", ReferenceRepo.asString(adj, "adjudicated_by"))
                    .addValue("adjudicatedAt", Sql.odt(ReferenceRepo.asInstant(adj, "adjudicated_at")))
                    .addValue("approvalRef", ReferenceRepo.asString(adj, "approval_ref")));
        }
        if (!batch.isEmpty()) {
            releases.insertAdjudications(batch.toArray(new SqlParameterSource[0]));
        }
    }

    /**
     * Freeze the approved concept-granularity evolution: only APPROVED edges
     * and bridge rows are part of a release, mirroring the rule that no
     * un-approved relation may feed frozen statistics.
     */
    private void freezeGranularity(UUID releaseId) {
        List<SqlParameterSource> hierarchyBatch = new ArrayList<>();
        for (Map<String, Object> h : evolution.listHierarchy("APPROVED")) {
            hierarchyBatch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("parentCode", ReferenceRepo.asString(h, "parent_concept_code"))
                    .addValue("childCode", ReferenceRepo.asString(h, "child_concept_code"))
                    .addValue("relationshipType", ReferenceRepo.asString(h, "relationship_type"))
                    .addValue("aggregationKind", ReferenceRepo.asString(h, "aggregation_kind"))
                    .addValue("modelCode", ReferenceRepo.asString(h, "model_code"))
                    .addValue("firmwareFrom", ReferenceRepo.asString(h, "firmware_from"))
                    .addValue("firmwareTo", ReferenceRepo.asString(h, "firmware_to"))
                    .addValue("firmwareFromSeq", ReferenceRepo.asInt(h, "firmware_from_seq"))
                    .addValue("firmwareToSeq", ReferenceRepo.asInt(h, "firmware_to_seq"))
                    .addValue("basis", ReferenceRepo.asString(h, "engineering_basis"))
                    .addValue("status", ReferenceRepo.asString(h, "status"))
                    .addValue("validFrom", Sql.odt(ReferenceRepo.asInstant(h, "valid_from")))
                    .addValue("validTo", Sql.odt(ReferenceRepo.asInstant(h, "valid_to")))
                    .addValue("approvedBy", ReferenceRepo.asString(h, "approved_by"))
                    .addValue("approvalRef", ReferenceRepo.asString(h, "approval_ref"))
                    .addValue("approvedAt", Sql.odt(ReferenceRepo.asInstant(h, "approved_at"))));
        }
        if (!hierarchyBatch.isEmpty()) {
            evolution.insertReleaseHierarchy(hierarchyBatch.toArray(new SqlParameterSource[0]));
        }

        List<SqlParameterSource> bridgeBatch = new ArrayList<>();
        for (Map<String, Object> b : evolution.listBridges("APPROVED")) {
            bridgeBatch.add(new MapSqlParameterSource()
                    .addValue("releaseId", releaseId)
                    .addValue("matrixCode", ReferenceRepo.asString(b, "matrix_code"))
                    .addValue("sourceCode", ReferenceRepo.asString(b, "source_concept_code"))
                    .addValue("targetCode", ReferenceRepo.asString(b, "target_concept_code"))
                    .addValue("modelCode", ReferenceRepo.asString(b, "model_code"))
                    .addValue("firmwareFrom", ReferenceRepo.asString(b, "firmware_from"))
                    .addValue("firmwareTo", ReferenceRepo.asString(b, "firmware_to"))
                    .addValue("firmwareFromSeq", ReferenceRepo.asInt(b, "firmware_from_seq"))
                    .addValue("firmwareToSeq", ReferenceRepo.asInt(b, "firmware_to_seq"))
                    .addValue("weight", Sql.decimal(b.get("weight")))
                    .addValue("basis", ReferenceRepo.asString(b, "engineering_basis"))
                    .addValue("status", ReferenceRepo.asString(b, "status"))
                    .addValue("validFrom", Sql.odt(ReferenceRepo.asInstant(b, "valid_from")))
                    .addValue("validTo", Sql.odt(ReferenceRepo.asInstant(b, "valid_to")))
                    .addValue("approvedBy", ReferenceRepo.asString(b, "approved_by"))
                    .addValue("approvalRef", ReferenceRepo.asString(b, "approval_ref"))
                    .addValue("approvedAt", Sql.odt(ReferenceRepo.asInstant(b, "approved_at"))));
        }
        if (!bridgeBatch.isEmpty()) {
            evolution.insertReleaseBridge(bridgeBatch.toArray(new SqlParameterSource[0]));
        }
    }

    /**
     * Deterministic hash over the frozen *business* content. Surrogate row ids
     * (release_id, id, child foreign keys) are excluded so the same approved
     * inputs always hash identically; verification ids are not content.
     */
    private static final Set<String> HASH_EXCLUDED = Set.of(
            "id", "release_id", "release_part_id", "created_at");

    private String contentHash(UUID releaseId) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("codes", canonical(releases.sourceCodes(releaseId)));
            content.put("terms", canonical(releases.terms(releaseId)));
            content.put("concepts", canonical(releases.concepts(releaseId)));
            content.put("firmware", canonical(releases.firmware(releaseId)));
            content.put("parts", canonical(releases.parts(releaseId)));
            content.put("inventory", canonical(releases.inventory(releaseId)));
            content.put("dispositions", canonical(releases.dispositions(releaseId)));
            content.put("adjudications", canonical(releases.adjudications(releaseId)));
            content.put("codeEntries", canonical(releases.allCodeEntries(releaseId)));
            content.put("termEntries", canonical(releases.allTermEntries(releaseId)));
            content.put("conceptHierarchy", canonical(evolution.releaseHierarchy(releaseId)));
            content.put("conceptBridges", canonical(evolution.releaseBridges(releaseId)));
            byte[] canonical = mapper.writer().with(
                    com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(content);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute release content hash", e);
        }
    }

    private List<String> canonical(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            java.util.TreeMap<String, Object> trimmed = new java.util.TreeMap<>();
            row.forEach((k, v) -> {
                if (!HASH_EXCLUDED.contains(k)) {
                    trimmed.put(k, v == null ? null : v.toString());
                }
            });
            out.add(trimmed.toString());
        }
        out.sort(String::compareTo);
        return out;
    }
}
