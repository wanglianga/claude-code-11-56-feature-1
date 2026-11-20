package com.windfarm.faultref.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for concept-granularity evolution: versioned hierarchy edges,
 * engineer-approved bridge matrix, unattributed (firmware-evidence-missing)
 * alarms, work-order granularity links and the frozen release counterparts.
 */
@Repository
public class EvolutionRepo {

    private static final RowMapper<Map<String, Object>> MAP = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        var md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp ts) {
                v = ts.toInstant();
            }
            m.put(md.getColumnLabel(i).toLowerCase(), v);
        }
        return m;
    };

    private final NamedParameterJdbcTemplate jdbc;

    public EvolutionRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- concept hierarchy ---------------------------------------------
    public UUID insertHierarchy(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into concept_hierarchy
                    (parent_concept_id, child_concept_id, relationship_type, aggregation_kind,
                     scope_model_id, firmware_from, firmware_to, firmware_from_seq, firmware_to_seq,
                     engineering_basis, status, valid_from, valid_to,
                     approved_by, approval_ref, approved_at)
                values
                    (:parentId, :childId, :relationshipType, :aggregationKind,
                     :modelId, :firmwareFrom, :firmwareTo, :firmwareFromSeq, :firmwareToSeq,
                     :basis, :status, :validFrom, :validTo,
                     :approvedBy, :approvalRef, :approvedAt)
                returning id
                """, p, UUID.class);
    }

    public Optional<Map<String, Object>> hierarchyById(UUID id) {
        return jdbc.query("select * from concept_hierarchy where id = :id",
                Map.of("id", id), MAP).stream().findFirst();
    }

    public void approveHierarchy(UUID id, String approvedBy, String approvalRef, Instant approvedAt) {
        jdbc.update("""
                update concept_hierarchy
                   set status = 'APPROVED', approved_by = :by, approval_ref = :ref, approved_at = :at
                 where id = :id
                """, new MapSqlParameterSource().addValue("by", approvedBy).addValue("ref", approvalRef)
                .addValue("at", Sql.odt(approvedAt)).addValue("id", id));
    }

    public void retireHierarchy(UUID id) {
        jdbc.update("update concept_hierarchy set status = 'RETIRED' where id = :id",
                Map.of("id", id));
    }

    public List<Map<String, Object>> listHierarchy(String status) {
        String sql = """
                select h.*, pc.code as parent_concept_code, pc.name as parent_concept_name,
                       cc.code as child_concept_code, cc.name as child_concept_name,
                       m.code as model_code, man.code as manufacturer_code
                from concept_hierarchy h
                join concept pc on pc.id = h.parent_concept_id
                join concept cc on cc.id = h.child_concept_id
                left join turbine_model m on m.id = h.scope_model_id
                left join manufacturer man on man.id = m.manufacturer_id
                """;
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (status != null) {
            sql += " where h.status = :status";
            p.addValue("status", status);
        }
        sql += " order by pc.code, cc.code, h.created_at";
        return jdbc.query(sql, p, MAP);
    }

    public List<Map<String, Object>> hierarchyForConcept(UUID conceptId) {
        return jdbc.query("""
                select h.*, pc.code as parent_concept_code, pc.name as parent_concept_name,
                       cc.code as child_concept_code, cc.name as child_concept_name,
                       m.code as model_code
                from concept_hierarchy h
                join concept pc on pc.id = h.parent_concept_id
                join concept cc on cc.id = h.child_concept_id
                left join turbine_model m on m.id = h.scope_model_id
                where (h.parent_concept_id = :cid or h.child_concept_id = :cid)
                order by h.status, h.created_at
                """, Map.of("cid", conceptId), MAP);
    }

    /**
     * Child concepts that have at least one hierarchy row but no APPROVED one:
     * fine codes without an approved engineering definition. They must never
     * enter automatic statistics.
     */
    public List<Map<String, Object>> undefinedChildConcepts() {
        return jdbc.query("""
                select c.id, c.code, c.name,
                       count(*) filter (where h.status = 'APPROVED') as approved_edges,
                       count(*) as total_edges
                from concept_hierarchy h
                join concept c on c.id = h.child_concept_id
                group by c.id, c.code, c.name
                having count(*) filter (where h.status = 'APPROVED') = 0
                order by c.code
                """, Map.of(), MAP);
    }

    // ---- bridge matrix --------------------------------------------------
    public UUID insertBridge(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into concept_bridge_matrix
                    (matrix_code, source_concept_id, target_concept_id, scope_model_id,
                     firmware_from, firmware_to, firmware_from_seq, firmware_to_seq,
                     weight, engineering_basis, status, valid_from, valid_to,
                     approved_by, approval_ref, approved_at)
                values
                    (:matrixCode, :sourceId, :targetId, :modelId,
                     :firmwareFrom, :firmwareTo, :firmwareFromSeq, :firmwareToSeq,
                     :weight, :basis, :status, :validFrom, :validTo,
                     :approvedBy, :approvalRef, :approvedAt)
                returning id
                """, p, UUID.class);
    }

    public Optional<Map<String, Object>> bridgeById(UUID id) {
        return jdbc.query("select * from concept_bridge_matrix where id = :id",
                Map.of("id", id), MAP).stream().findFirst();
    }

    public void approveBridge(UUID id, String approvedBy, String approvalRef, Instant approvedAt) {
        jdbc.update("""
                update concept_bridge_matrix
                   set status = 'APPROVED', approved_by = :by, approval_ref = :ref, approved_at = :at
                 where id = :id
                """, new MapSqlParameterSource().addValue("by", approvedBy).addValue("ref", approvalRef)
                .addValue("at", Sql.odt(approvedAt)).addValue("id", id));
    }

    public void retireBridge(UUID id) {
        jdbc.update("update concept_bridge_matrix set status = 'RETIRED' where id = :id",
                Map.of("id", id));
    }

    public List<Map<String, Object>> listBridges(String status) {
        String sql = """
                select b.*, sc.code as source_concept_code, sc.name as source_concept_name,
                       tc.code as target_concept_code, tc.name as target_concept_name,
                       m.code as model_code
                from concept_bridge_matrix b
                join concept sc on sc.id = b.source_concept_id
                join concept tc on tc.id = b.target_concept_id
                left join turbine_model m on m.id = b.scope_model_id
                """;
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (status != null) {
            sql += " where b.status = :status";
            p.addValue("status", status);
        }
        sql += " order by b.matrix_code, sc.code";
        return jdbc.query(sql, p, MAP);
    }

    // ---- unattributed alarms --------------------------------------------
    public void upsertUnattributed(UUID alarmId, String reasonCode, String rawFirmwareHint, String note) {
        jdbc.update("""
                insert into unattributed_alarm (alarm_id, reason_code, raw_firmware_hint, note)
                values (:alarmId, :reason, :hint, :note)
                on conflict (alarm_id) do nothing
                """, new MapSqlParameterSource().addValue("alarmId", alarmId)
                .addValue("reason", reasonCode).addValue("hint", rawFirmwareHint).addValue("note", note));
    }

    public List<Map<String, Object>> listUnattributed(boolean openOnly) {
        String sql = """
                select ua.*, a.external_id, a.raw_code, a.occurred_at, a.received_at,
                       a.ingest_type, u.code as unit_code, ss.code as source_system_code
                from unattributed_alarm ua
                join alarm a on a.id = ua.alarm_id
                join unit u on u.id = a.unit_id
                join source_system ss on ss.id = a.source_system_id
                """;
        if (openOnly) {
            sql += " where ua.resolved_at is null";
        }
        sql += " order by a.occurred_at";
        return jdbc.query(sql, Map.of(), MAP);
    }

    public Optional<Map<String, Object>> unattributedById(UUID id) {
        return jdbc.query("select * from unattributed_alarm where id = :id",
                Map.of("id", id), MAP).stream().findFirst();
    }

    public void resolveUnattributed(UUID id, String resolutionNote, Instant resolvedAt) {
        jdbc.update("""
                update unattributed_alarm set resolved_at = :at, resolution_note = :note
                where id = :id
                """, new MapSqlParameterSource().addValue("at", Sql.odt(resolvedAt))
                .addValue("note", resolutionNote).addValue("id", id));
    }

    // ---- work order granularity links -----------------------------------
    public UUID insertWorkOrderLink(MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                insert into work_order_concept_link
                    (work_order_id, concept_id, link_type, note, created_by)
                values
                    (:workOrderId, :conceptId, :linkType, :note, :createdBy)
                returning id
                """, p, UUID.class);
    }

    public List<Map<String, Object>> linksForWorkOrder(UUID workOrderId) {
        return jdbc.query("""
                select l.*, c.code as concept_code, c.name as concept_name
                from work_order_concept_link l
                join concept c on c.id = l.concept_id
                where l.work_order_id = :wid
                order by l.created_at
                """, Map.of("wid", workOrderId), MAP);
    }

    // ---- trend source data ----------------------------------------------
    /**
     * Per-alarm primary concept rows for one run, joined with the unit model
     * and firmware evidence. Unattributed alarms and fine codes without an
     * approved engineering definition are flagged, never silently dropped:
     * the caller reports them as exclusions.
     */
    public List<Map<String, Object>> runAlarmConcepts(UUID runId) {
        return jdbc.query("""
                select res.alarm_id, a.external_id, a.occurred_at, a.firmware_version_id,
                       fv.version as firmware_version, fv.sequence as firmware_sequence,
                       u.id as unit_id, u.code as unit_code, m.id as model_id, m.code as model_code,
                       res.primary_concept_id, c.code as concept_code, c.name as concept_name,
                       case when ua.id is null then false else true end as unattributed,
                       case when und.id is null then false else true end as undefined_subcode
                from diagnosis_result res
                join alarm a on a.id = res.alarm_id
                join unit u on u.id = a.unit_id
                join turbine_model m on m.id = u.model_id
                left join firmware_version fv on fv.id = a.firmware_version_id
                left join concept c on c.id = res.primary_concept_id
                left join unattributed_alarm ua on ua.alarm_id = a.id and ua.resolved_at is null
                left join (
                    select distinct h.child_concept_id as id
                    from concept_hierarchy h
                    group by h.child_concept_id
                    having count(*) filter (where h.status = 'APPROVED') = 0
                ) und on und.id = res.primary_concept_id
                where res.run_id = :rid
                order by a.occurred_at
                """, Map.of("rid", runId), MAP);
    }

    // ---- frozen release counterparts ------------------------------------
    public int[] insertReleaseHierarchy(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_concept_hierarchy
                    (release_id, parent_concept_code, child_concept_code, relationship_type,
                     aggregation_kind, model_code, firmware_from, firmware_to,
                     firmware_from_seq, firmware_to_seq, engineering_basis, status,
                     valid_from, valid_to, approved_by, approval_ref, approved_at)
                values
                    (:releaseId, :parentCode, :childCode, :relationshipType, :aggregationKind,
                     :modelCode, :firmwareFrom, :firmwareTo, :firmwareFromSeq, :firmwareToSeq,
                     :basis, :status, :validFrom, :validTo,
                     :approvedBy, :approvalRef, :approvedAt)
                """, batch);
    }

    public int[] insertReleaseBridge(SqlParameterSource[] batch) {
        return jdbc.batchUpdate("""
                insert into release_concept_bridge
                    (release_id, matrix_code, source_concept_code, target_concept_code, model_code,
                     firmware_from, firmware_to, firmware_from_seq, firmware_to_seq, weight,
                     engineering_basis, status, valid_from, valid_to,
                     approved_by, approval_ref, approved_at)
                values
                    (:releaseId, :matrixCode, :sourceCode, :targetCode, :modelCode,
                     :firmwareFrom, :firmwareTo, :firmwareFromSeq, :firmwareToSeq, :weight,
                     :basis, :status, :validFrom, :validTo,
                     :approvedBy, :approvalRef, :approvedAt)
                """, batch);
    }

    public List<Map<String, Object>> releaseHierarchy(UUID releaseId) {
        return jdbc.query("select * from release_concept_hierarchy where release_id = :rid",
                Map.of("rid", releaseId), MAP);
    }

    public List<Map<String, Object>> releaseBridges(UUID releaseId) {
        return jdbc.query("select * from release_concept_bridge where release_id = :rid",
                Map.of("rid", releaseId), MAP);
    }
}
