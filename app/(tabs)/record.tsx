import { useFocusEffect } from "expo-router";
import { useCallback, useEffect, useState } from "react";
import { PermissionsAndroid } from "react-native";
import { startRecording, stopRecording } from "@/modules/recorder";
import ContentContainer from "@/components/ContentContainer";
import { StyledButton } from "@/components/StyledButton";
import { StyledText } from "@/components/StyledText";
import { usePersistedState } from "@/hooks/usePersistedState";
import { readDay, todayKey } from "@/services/trackStore";
import { n } from "@/utils/scaling";

const INTERVAL_OPTIONS = ["5s", "10s", "30s", "60s"] as const;
const INTERVAL_MS: Record<string, number> = {
  "5s": 5_000,
  "10s": 10_000,
  "30s": 30_000,
  "60s": 60_000,
};

export default function RecordScreen() {
  // Persisted user intent — the single source of truth for the UI.
  const [recordOn, setRecordOn] = usePersistedState("recordingOn", false);
  const [interval, setIntervalPref] = usePersistedState("recordInterval", "10s");
  const [pointsToday, setPointsToday] = useState(0);

  // Whenever intent is on (including after app restart), ensure the
  // service is running. startRecording is idempotent.
  useEffect(() => {
    if (recordOn) {
      startRecording(INTERVAL_MS[interval] ?? 10_000);
    }
  }, [recordOn, interval]);

  useFocusEffect(
    useCallback(() => {
      readDay(todayKey()).then((pts) => setPointsToday(pts.length));
    }, [])
  );

  const toggle = async () => {
    if (recordOn) {
      stopRecording();
      await setRecordOn(false);
      return;
    }
    const fine = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
    );
    if (fine !== PermissionsAndroid.RESULTS.GRANTED) return;
    await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    ).catch(() => undefined);
    startRecording(INTERVAL_MS[interval] ?? 10_000);
    await setRecordOn(true);
  };

  return (
    <ContentContainer headerTitle="Record" hideBackButton>
      <StyledButton
        onPress={toggle}
        text={recordOn ? "Stop Recording" : "Start Recording"}
      />
      <StyledText style={{ fontSize: n(14), marginTop: n(16) }}>
        GPS Interval
      </StyledText>
      {INTERVAL_OPTIONS.map((o) => (
        <StyledButton
          key={o}
          onPress={() => setIntervalPref(o)}
          selected={interval === o}
          text={o}
        />
      ))}
      <StyledText style={{ fontSize: n(14), marginTop: n(16) }}>
        {recordOn
          ? "Recording — leave it running, the fog clears itself."
          : "Not recording."}
      </StyledText>
      <StyledText style={{ fontSize: n(14), marginTop: n(8) }}>
        Points today: {pointsToday}
      </StyledText>
    </ContentContainer>
  );
}
