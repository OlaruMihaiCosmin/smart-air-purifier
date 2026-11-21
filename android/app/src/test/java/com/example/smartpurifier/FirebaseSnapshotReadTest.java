package com.example.smartpurifier;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.InternalHelpers;
import com.google.firebase.database.snapshot.IndexedNode;
import com.google.firebase.database.snapshot.NodeUtilities;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class FirebaseSnapshotReadTest {
    private DataSnapshot snapshot(Object value) {
        // Citirea valorii nu foloseste referinta; nodul este cel real din SDK, fara retea.
        return InternalHelpers.createDataSnapshot(null, IndexedNode.from(NodeUtilities.NodeFromJSON(value)));
    }

    @Test public void firmwareObjectSurvivesTheActualSdkReadPath() {
        Map<String, Object> report = new HashMap<>();
        report.put("versiune", "2026.09.28.1");
        report.put("build", "Sep 28 2026 23:10:00");
        report.put("raportat_la", 1800000000000L);
        DataSnapshot snapshot = snapshot(report);
        assertThrows(DatabaseException.class, () -> snapshot.getValue(Map.class));
        Object decoded = FirebaseSnapshotValuesKt.valoareDinSnapshot(snapshot, Map.class);
        assertEquals(report, decoded);
        assertNotNull(ReliabilityActionsKt.citesteVersiuneFirmware(decoded));
    }

    @Test public void scalarAndMissingSnapshotKeepTheirExistingSemantics() {
        assertEquals(42L, FirebaseSnapshotValuesKt.valoareDinSnapshot(snapshot(42L), Long.class));
        assertEquals(false, FirebaseSnapshotValuesKt.valoareDinSnapshot(snapshot(false), Boolean.class));
        assertNull(FirebaseSnapshotValuesKt.valoareDinSnapshot(snapshot(null), Map.class));
        assertThrows(DatabaseException.class, () -> FirebaseSnapshotValuesKt.valoareDinSnapshot(snapshot("wrong"), Long.class));
    }

    @Test public void diagnosticReportAcceptsTheActualSdkObjectWithNativeNumbers() {
        Map<String, Object> report = new HashMap<>();
        report.put("schema", 1L);
        report.put("raportat_la", 1800000000000L);
        report.put("firmware_versiune", "2026.09.29.1");
        report.put("firmware_build", "Sep 29 2026 10:20:30");
        report.put("heap_liber_bytes", 100000L);
        Object decoded = FirebaseSnapshotValuesKt.valoareDinSnapshot(snapshot(report), Map.class);
        String text = DiagnosticReportKt.genereazaRaportDiagnostic(decoded, null, "2026.09.29.1", "build",
            1800000030000L, true, 1800000030000L, java.time.ZoneOffset.UTC);
        assertTrue(text.contains("ESP32: 2026.09.29.1"));
        assertTrue(text.contains("Diagnostic: recent (30 s)"));
        assertTrue(text.contains("Heap liber: 100000 bytes"));
    }
}
