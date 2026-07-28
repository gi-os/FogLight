import { useCallback, useState } from "react";
import { useFocusEffect } from "expo-router";
import ContentContainer from "@/components/ContentContainer";
import { StyledText } from "@/components/StyledText";
import { listDays, readDay } from "@/services/trackStore";
import { routeTotalMiles } from "@/utils/geo";
import { n } from "@/utils/scaling";

type DayRow = { day: string; points: number; miles: number };

export default function TracksScreen() {
  const [rows, setRows] = useState<DayRow[]>([]);

  useFocusEffect(
    useCallback(() => {
      let cancelled = false;
      (async () => {
        const days = await listDays();
        const built: DayRow[] = [];
        for (const day of days.slice(0, 30)) {
          const pts = await readDay(day);
          built.push({
            day,
            points: pts.length,
            miles: routeTotalMiles(pts.map((p) => [p.lng, p.lat])),
          });
        }
        if (!cancelled) setRows(built);
      })();
      return () => {
        cancelled = true;
      };
    }, [])
  );

  return (
    <ContentContainer headerTitle="Tracks" hideBackButton>
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
    </ContentContainer>
  );
}
