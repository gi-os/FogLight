import { useFocusEffect } from "expo-router";
import { useCallback, useState } from "react";
import ContentContainer from "@/components/ContentContainer";
import { StyledButton } from "@/components/StyledButton";
import { StyledText } from "@/components/StyledText";
import {
  importNewGpx,
  isConnected,
  listImported,
} from "@/services/cloud/dropbox";
import { fowConvertNow, fowInspect, fowRenderTile, tileSyncReport } from "@/modules/recorder";
import * as FileSystem from "expo-file-system/legacy";
import { fowDir, fowDirPath } from "@/services/cloud/dropbox";
import { scanFowTiles } from "@/utils/fog/fowSource";
import { buildTrail, listDays, readDay } from "@/services/trackStore";
import { n } from "@/utils/scaling";

type DayRow = { day: string; points: number; miles: number };

export default function TracksScreen() {
  const [rows, setRows] = useState<DayRow[]>([]);
  const [imported, setImported] = useState<string[]>([]);
  const [connected, setConnected] = useState(false);
  const [importStatus, setImportStatus] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const [convertStatus, setConvertStatus] = useState<string | null>(null);
  const [converting, setConverting] = useState(false);

  const refresh = useCallback(async () => {
    const days = await listDays();
    const built: DayRow[] = [];
    for (const day of days.slice(0, 30)) {
      const pts = await readDay(day);
      built.push({
        day,
        points: pts.length,
        miles: buildTrail(pts).miles,
      });
    }
    setRows(built);
    setImported(await listImported());
    setConnected(await isConnected());
    const report = tileSyncReport();
    if (report) setConvertStatus(`Last sync — ${report}`);
  }, []);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh])
  );

  const doImport = async () => {
    setImporting(true);
    setImportStatus("Checking Dropbox…");
    try {
      const r = await importNewGpx();
      let renderCheck = "";
      if (r.fowTotal > 0) {
        try {
          const tiles = await scanFowTiles();
          if (tiles.length > 0) {
            const test = await fowRenderTile(tiles[0].path, 1024, 0xb4ffffff);
            renderCheck = test ? " Render check: OK." : " Render check: FAILED.";
            if (!test) {
              renderCheck += ` Inspect: ${await fowInspect(tiles[0].path)}`;
            }
          } else {
            renderCheck = " Render check: no tiles on disk.";
            try {
              const names = await FileSystem.readDirectoryAsync(fowDir());
              if (names.length > 0) {
                renderCheck += ` Inspect(${names[0]}): ${await fowInspect(
                  `${fowDirPath()}/${names[0]}`
                )}`;
              }
            } catch {
              // ignore
            }
          }
        } catch (e) {
          renderCheck = ` Render check: ${e instanceof Error ? e.message : "error"}.`;
        }
      }
      setImportStatus(
        `GPX: +${r.gpxAdded} new (${r.gpxTotal} total). ` +
          `Fog of World tiles: +${r.fowAdded} new, ${r.fowFailed} failed (${r.fowTotal} total).` +
          (r.firstError ? ` First error: ${r.firstError}` : "") +
          renderCheck
      );
      await refresh();
    } catch (e) {
      setImportStatus(e instanceof Error ? e.message : "Import failed.");
    } finally {
      setImporting(false);
    }
  };

  return (
    <ContentContainer headerTitle="Tracks" hideBackButton>
      {connected ? (
        <StyledButton
          onPress={importing ? undefined : doImport}
          text={importing ? "Importing…" : "Import from Dropbox"}
        />
      ) : (
        <StyledText style={{ fontSize: n(13), opacity: 0.7 }}>
          Connect Dropbox in Settings to import GPX files.
        </StyledText>
      )}
      {importStatus && (
        <StyledText style={{ fontSize: n(13), marginTop: n(8) }}>
          {importStatus}
        </StyledText>
      )}
      {connected && (
        <StyledButton
          onPress={
            converting
              ? undefined
              : async () => {
                  setConverting(true);
                  setConvertStatus("Converting tracks to tiles…");
                  try {
                    setConvertStatus(await fowConvertNow());
                  } finally {
                    setConverting(false);
                  }
                }
          }
          text={converting ? "Converting…" : "Convert & Upload Tiles Now"}
        />
      )}
      {convertStatus && (
        <StyledText style={{ fontSize: n(13), marginTop: n(8) }}>
          {convertStatus}
        </StyledText>
      )}

      <StyledText style={{ fontSize: n(16), marginTop: n(20), marginBottom: n(8) }}>
        RECORDED
      </StyledText>
      {rows.length === 0 && (
        <StyledText style={{ fontSize: n(14) }}>
          No tracks yet. Start recording from the Record tab.
        </StyledText>
      )}
      {rows.map((row) => (
        <StyledText key={row.day} style={{ fontSize: n(15), marginBottom: n(10) }}>
          {row.day} — {row.points} pts, {row.miles.toFixed(1)} mi
        </StyledText>
      ))}

      {imported.length > 0 && (
        <>
          <StyledText style={{ fontSize: n(16), marginTop: n(20), marginBottom: n(8) }}>
            IMPORTED
          </StyledText>
          {imported.map((name) => (
            <StyledText key={name} style={{ fontSize: n(15), marginBottom: n(10) }}>
              {name}
            </StyledText>
          ))}
        </>
      )}
    </ContentContainer>
  );
}
