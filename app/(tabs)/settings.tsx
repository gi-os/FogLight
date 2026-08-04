import Geolocation from "@react-native-community/geolocation";
import Constants from "expo-constants";
import { PermissionsAndroid } from "react-native";
import {
  clearPrivacyNetwork,
  clearPrivacyZone,
  motionGating,
  type PowerState,
  powerState,
  setMotionGating,
  setPrivacyNetwork,
  setPrivacyZone,
  setStillAfterMinutes,
  setTileSync,
  stillAfterMinutes,
} from "@/modules/recorder";
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
  const [netHome, setNetHome] = usePersistedState<string | null>("netHome", null);
  const [netWork, setNetWork] = usePersistedState<string | null>("netWork", null);
  const [zoneHome, setZoneHome] = usePersistedState<string | null>("zoneHome", null);
  const [zoneWork, setZoneWork] = usePersistedState<string | null>("zoneWork", null);
  const [zoneStatus, setZoneStatus] = useState<string | null>(null);
  const [tileSyncOn, setTileSyncOn] = usePersistedState("tileSyncEnabled", false);
  const [appKey, setAppKeyState] = useState("");
  const [connected, setConnected] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [gating, setGating] = useState(true);
  const [stillAfter, setStillAfter] = useState(6);
  const [power, setPower] = useState<PowerState>("ACTIVE");
  const version = Constants.expoConfig?.version;
  const headerTitle = version ? `Settings (v${version})` : "Settings";

  useFocusEffect(
    useCallback(() => {
      getAppKey().then((k) => setAppKeyState(k ?? ""));
      isConnected().then(setConnected);
      setStatus(null);
      // Read from native, not from a stored copy — the service is what decided, and it decided
      // while this screen was closed.
      setGating(motionGating());
      setStillAfter(stillAfterMinutes());
      setPower(powerState());
    }, [])
  );

  const POWER_LABEL: Record<PowerState, string> = {
    ACTIVE: "GPS on — recording.",
    PAUSED_ZONE: "GPS off — you're on a home or work network.",
    PAUSED_STILL: "GPS off — nothing has moved. Motion will wake it.",
  };

  const changeGating = (enabled: boolean) => {
    setGating(enabled);
    setMotionGating(enabled);
    setPower(powerState());
  };

  const changeStillAfter = (minutes: number) => {
    setStillAfter(minutes);
    setStillAfterMinutes(minutes);
  };

  /**
   * Mark the Wi-Fi you are on as home or work.
   *
   * The primary way a zone is matched now. The coordinate version below is kept as a backstop, so
   * turning Wi-Fi off cannot quietly start recording your address — but this is the one that works
   * indoors, where the GPS fix that "Set to Current Location" needs is exactly what you do not have.
   */
  const setZoneNetwork = (name: "home" | "work") => {
    const ssid = setPrivacyNetwork(name);
    if (!ssid) {
      setZoneStatus("Android would not name the network — check location is on.");
      return;
    }
    if (name === "home") setNetHome(ssid);
    else setNetWork(ssid);
    setZoneStatus(null);
  };

  const clearZoneNetwork = (name: "home" | "work") => {
    clearPrivacyNetwork(name);
    if (name === "home") setNetHome(null);
    else setNetWork(null);
  };

  const setZoneHere = async (name: "home" | "work") => {
    const fine = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
    );
    if (fine !== PermissionsAndroid.RESULTS.GRANTED) return;
    setZoneStatus(`Getting location for ${name}…`);
    Geolocation.getCurrentPosition(
      (pos) => {
        setPrivacyZone(name, pos.coords.latitude, pos.coords.longitude, 500);
        const label = `${pos.coords.latitude.toFixed(4)}, ${pos.coords.longitude.toFixed(4)}`;
        if (name === "home") setZoneHome(label);
        else setZoneWork(label);
        setZoneStatus(null);
      },
      () => setZoneStatus("Could not get a GPS fix — try outside."),
      { enableHighAccuracy: true, timeout: 30_000 }
    );
  };

  const clearZone = (name: "home" | "work") => {
    clearPrivacyZone(name);
    if (name === "home") setZoneHome(null);
    else setZoneWork(null);
  };

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
        BATTERY
      </StyledText>
      <StyledText style={{ fontSize: n(12), opacity: 0.6, marginBottom: n(8) }}>
        {POWER_LABEL[power]}
      </StyledText>
      <ToggleSwitch
        label="Pause When Still"
        onValueChange={changeGating}
        value={gating}
      />
      {gating && (
        <SliderRow
          format={(v) => `${v} min`}
          label="Still After"
          max={30}
          min={2}
          onChange={changeStillAfter}
          step={1}
          value={stillAfter}
        />
      )}

      <StyledText style={{ fontSize: n(16), marginTop: n(24), marginBottom: n(8) }}>
        PRIVACY ZONES
      </StyledText>
      <StyledText style={{ fontSize: n(12), opacity: 0.6, marginBottom: n(8) }}>
        The GPS switches off on these networks — not just the track. Within 500m of these spots the
        fixes are dropped, but the radio stays on, because leaving a circle takes a fix to notice.
      </StyledText>
      <StyledButton
        onPress={() => (netHome ? clearZoneNetwork("home") : setZoneNetwork("home"))}
        text={netHome ? `Clear Home Wi-Fi (${netHome})` : "Set Home to This Wi-Fi"}
      />
      <StyledButton
        onPress={() => (netWork ? clearZoneNetwork("work") : setZoneNetwork("work"))}
        text={netWork ? `Clear Work Wi-Fi (${netWork})` : "Set Work to This Wi-Fi"}
      />
      <StyledButton
        onPress={() => (zoneHome ? clearZone("home") : setZoneHere("home"))}
        text={zoneHome ? `Clear Home area (${zoneHome})` : "Also set Home area from GPS"}
      />
      <StyledButton
        onPress={() => (zoneWork ? clearZone("work") : setZoneHere("work"))}
        text={zoneWork ? `Clear Work area (${zoneWork})` : "Also set Work area from GPS"}
      />
      {zoneStatus && (
        <StyledText style={{ fontSize: n(13), marginTop: n(8) }}>{zoneStatus}</StyledText>
      )}

      <StyledText style={{ fontSize: n(16), marginTop: n(24), marginBottom: n(8) }}>
        DROPBOX
      </StyledText>
      {connected ? (
        <>
          <StyledText style={{ fontSize: n(14), marginBottom: n(8) }}>
            Connected.
          </StyledText>
          <ToggleSwitch
            label="Nightly Tile Sync"
            onValueChange={(v: boolean) => {
              setTileSyncOn(v);
              setTileSync(v);
            }}
            value={tileSyncOn}
          />
          <StyledText style={{ fontSize: n(12), opacity: 0.6, marginBottom: n(8) }}>
            Converts finished days into Fog of World tiles overnight (only
            newly explored areas) and uploads them to Dropbox.
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
