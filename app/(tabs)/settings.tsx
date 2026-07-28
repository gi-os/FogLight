import Constants from "expo-constants";
import { useFocusEffect } from "expo-router";
import { useCallback, useState } from "react";
import ContentContainer from "@/components/ContentContainer";
import { StyledButton } from "@/components/StyledButton";
import { StyledText } from "@/components/StyledText";
import { TextInput } from "@/components/TextInput";
import { ToggleSwitch } from "@/components/ToggleSwitch";
import { useInvertColors } from "@/contexts/InvertColorsContext";
import {
  disconnect,
  getAppKey,
  isConnected,
  setAppKey,
  startAuth,
} from "@/services/cloud/dropbox";
import { n } from "@/utils/scaling";

export default function SettingsScreen() {
  const { invertColors, setInvertColors } = useInvertColors();
  const [appKey, setAppKeyState] = useState("");
  const [connected, setConnected] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const version = Constants.expoConfig?.version;
  const headerTitle = version ? `Settings (v${version})` : "Settings";

  useFocusEffect(
    useCallback(() => {
      getAppKey().then((k) => setAppKeyState(k ?? ""));
      isConnected().then(setConnected);
      setStatus(null);
    }, [])
  );

  const connect = async () => {
    try {
      await setAppKey(appKey);
      await startAuth();
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Could not start Dropbox auth.");
    }
  };

  const doDisconnect = async () => {
    await disconnect();
    setConnected(false);
  };

  return (
    <ContentContainer headerTitle={headerTitle} hideBackButton>
      <ToggleSwitch
        label="Invert Colours"
        onValueChange={setInvertColors}
        value={invertColors}
      />

      <StyledText style={{ fontSize: n(16), marginTop: n(24), marginBottom: n(8) }}>
        DROPBOX
      </StyledText>
      {connected ? (
        <>
          <StyledText style={{ fontSize: n(14), marginBottom: n(8) }}>
            Connected.
          </StyledText>
          <StyledButton onPress={doDisconnect} text="Disconnect Dropbox" />
        </>
      ) : (
        <>
          <TextInput
            onChangeText={setAppKeyState}
            placeholder="Dropbox app key"
            value={appKey}
          />
          <StyledButton onPress={connect} text="Connect Dropbox" />
          <StyledText style={{ fontSize: n(12), marginTop: n(8), opacity: 0.6 }}>
            Create a Scoped/App-folder app at dropbox.com/developers, enable
            files.metadata.read + files.content.read scopes, add redirect URI
            lightfog://oauth, then paste the app key here. Drop GPX files or a
            copy of your Fog of World Sync folder into Apps/LightFog to import.
          </StyledText>
        </>
      )}
      {status && (
        <StyledText style={{ fontSize: n(13), marginTop: n(8) }}>{status}</StyledText>
      )}

      <StyledText style={{ fontSize: n(12), marginTop: n(24), opacity: 0.5 }}>
        Map data © OpenStreetMap contributors
      </StyledText>
    </ContentContainer>
  );
}
