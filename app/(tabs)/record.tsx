import { useFocusEffect } from "expo-router";
import { useCallback, useEffect, useState } from "react";
import { PermissionsAndroid } from "react-native";
import {
  type PowerState,
  powerState,
  recordingError,
  startRecording,
  stopRecording,
} from "@/modules/recorder";
import ContentContainer from "@/components/ContentContainer";
import { StyledButton } from "@/components/StyledButton";
import { StyledText } from "@/components/StyledText";
import { usePersistedState } from "@/hooks/usePersistedState";
import { readDay, todayKey } from "@/services/trackStore";
import { n } from "@/utils/scaling";

/**
 * What "on" looks like when the GPS is deliberately off.
 *
 * Recording on and no points arriving is the same picture as recording broken, and the difference is
 * the whole feature — so the pause says so here rather than leaving the old unconditional
 * "Recording" to be quietly wrong for most of the day.
 */
const RECORDING_LABEL: Record<PowerState, string> = {
  ACTIVE: "Recording — leave it running, the fog clears itself.",
  PAUSED_ZONE: "Paused — on a home or work network. GPS is off.",
  PAUSED_STILL: "Paused — nothing has moved. Motion turns the GPS back on.",
};

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
  const [problem, setProblem] = useState<string | null>(null);
  const [power, setPower] = useState<PowerState>("ACTIVE");

  // Whenever intent is on (including after app restart), ensure the service is running — and take
  // no for an answer. A refusal is shown rather than retried: the native side only refuses for
  // reasons another attempt won't change, and a UI that keeps asking is what turned one failed
  // start into hundreds.
  useEffect(() => {
    if (!recordOn) {
      setProblem(null);
      return;
    }
    setProblem(startRecording(INTERVAL_MS[interval] ?? 10_000));
  }, [recordOn, interval]);

  useFocusEffect(
    useCallback(() => {
      readDay(todayKey()).then((pts) => setPointsToday(pts.length));
      setPower(powerState());
      // The service may have stood down while we were away — it says why, and it survives the app
      // being killed, so this is the one place the reason can actually be read.
      setProblem((current) => current ?? recordingError());
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
    if (fine !== PermissionsAndroid.RESULTS.GRANTED) {
      setProblem("Location permission is needed to record.");
      return;
    }
    await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    ).catch(() => undefined);
    const refused = startRecording(INTERVAL_MS[interval] ?? 10_000);
    setProblem(refused);
    // Intent follows reality: leaving it on after a refusal is what makes every later launch try
    // again, which is the loop.
    if (!refused) await setRecordOn(true);
  };

  return (
    <ContentContainer headerTitle="Record" hideBackButton>
      <StyledButton
        onPress={toggle}
        text={recordOn ? "Stop Recording" : "Start Recording"}
      />
      {problem ? (
        <StyledText style={{ fontSize: n(14), marginTop: n(12) }}>
          {`Not recording — ${problem}`}
        </StyledText>
      ) : null}
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
        {recordOn ? RECORDING_LABEL[power] : "Not recording."}
      </StyledText>
      <StyledText style={{ fontSize: n(14), marginTop: n(8) }}>
        Points today: {pointsToday}
      </StyledText>
    </ContentContainer>
  );
}
