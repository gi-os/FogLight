import Constants from "expo-constants";
import { useFocusEffect } from "expo-router";
import { useCallback, useState } from "react";
import ContentContainer from "@/components/ContentContainer";
import { SliderRow } from "@/components/SliderRow";
import { StyledButton } from "@/components/StyledButton";
import { StyledText } from "@/components/StyledText";
import { TextInput } from "@/components/TextInput";
import { ToggleSwitch } from "@/components/ToggleSwitch";
import { useInvertColors } from "@/contexts/InvertColorsContext";
import { usePersistedState } from "@/hooks/usePersistedState";
import {
  disconnect,
  getAppKey,
  isConnected,
  setAppKey,
  startAuth,
} from "@/services/cloud/dropbox";
import { n } from "@/utils/scaling";

const FOG_COLORS = [
  { label: "Black", value: "black" },
  { label: "Dark Grey", value: "darkgrey" },
  { label: "Light Grey", value: "lightgrey" },
  { label: "White", value: "white" },
] as const;

const FOG_STYLES = [
  { label: "Smooth", value: "smooth" },
  { label: "Pixels", value: "pixel" },
] as const;

export default function SettingsScreen() {
  const { invertColors, setInvertColors } = useInvertColors();
  const [fogDensity, setFogDensity] = usePersistedState("fogDensity", 78);
  const [fogDebugOn, setFogDebugOn] = usePersistedState("fogDebugEnabled", false);
  const [fogStyle, setFogStyle] = usePersistedState("fogStyle", "smooth");
  const [fogScale2x, setFogScale2x] = usePersistedState("fogScale2x", true);
  const [fogBlurRadius, setFogBlurRadius] = usePersistedState("fogBlurRadius", 1);
  const [lightMap, setLightMap] = usePersistedState("lightMap", false);
  const [fogColorName, setFogColorName] = usePersistedState("fogColorName", "black");
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

      <ToggleSwitch
        label="White Map"
        onValueChange={setLightMap}
        value={lightMap}
      />

      <SliderRow
        format={(v) => `${v}%`}
        label="Fog Density"
        max={95}
        min={30}
        onChange={setFogDensity}
        value={fogDensity}
      />

      <StyledText style={{ fontSize: n(14), marginTop: n(16) }}>
        Fog Colour
      </StyledText>
      {FOG_COLORS.map((o) => (
        <StyledButton
          key={o.value}
          onPress={() => setFogColorName(o.value as string)}
          selected={fogColorName === o.value}
          text={o.label}
        />
      ))}

      <StyledText style={{ fontSize: n(14), marginTop: n(16) }}>
        Fog Style
      </StyledText>
      {FOG_STYLES.map((o) => (
        <StyledButton
          key={o.value}
          onPress={() => setFogStyle(o.value as string)}
          selected={fogStyle === o.value}
          text={o.label}
        />
      ))}
      {fogStyle === "pixel" && (
        <ToggleSwitch
          label="Pixel Smoothing (Scale2x)"
          onValueChange={setFogScale2x}
          value={fogScale2x}
        />
      )}
      {fogStyle === "smooth" && (
        <SliderRow
          format={(v) => (v === 0 ? "Off" : String(v))}
          label="Edge Blur"
          max={6}
          min={0}
          onChange={setFogBlurRadius}
          step={1}
          value={fogBlurRadius}
        />
      )}

      <ToggleSwitch
        label="Fog Debug Overlay"
        onValueChange={setFogDebugOn}
        value={fogDebugOn}
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
