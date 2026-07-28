import { useCallback, useState } from "react";
import { PermissionsAndroid } from "react-native";
import { useFocusEffect } from "expo-router";
import { isRecording, startRecording, stopRecording } from "recorder";
import ContentContainer from "@/components/ContentContainer";
import { StyledButton } from "@/components/StyledButton";
import { StyledText } from "@/components/StyledText";
import { OptionsSelector } from "@/components/OptionsSelector";
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
  const [running, setRunning] = useState(false);
  const [pointsToday, setPointsToday] = useState(0);
  const [interval, setIntervalPref] = usePersistedState("recordInterval", "10s");

  useFocusEffect(
    useCallback(() => {
      setRunning(isRecording());
      readDay(todayKey()).then((pts) => setPointsToday(pts.length));
    }, [])
  );

  const toggle = async () => {
    if (running) {
      stopRecording();
      setRunning(false);
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
    setRunning(true);
  };

  return (
    <ContentContainer headerTitle="Record" hideBackButton>
      <StyledButton
        onPress={toggle}
        text={running ? "Stop Recording" : "Start Recording"}
      />
      <StyledText style={{ fontSize: n(14), marginTop: n(16) }}>
        GPS Interval
      </StyledText>
      <OptionsSelector
        onSelect={(value: string) => setIntervalPref(value)}
        options={INTERVAL_OPTIONS.map((o) => ({ label: o, value: o }))}
        selectedValue={interval}
      />
      <StyledText style={{ fontSize: n(14), marginTop: n(16) }}>
        {running ? "Recording — leave it running, the fog clears itself." : "Not recording."}
      </StyledText>
      <StyledText style={{ fontSize: n(14), marginTop: n(8) }}>
        Points today: {pointsToday}
      </StyledText>
    </ContentContainer>
  );
}
