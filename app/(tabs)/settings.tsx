import Constants from "expo-constants";
import ContentContainer from "@/components/ContentContainer";
import { StyledText } from "@/components/StyledText";
import { ToggleSwitch } from "@/components/ToggleSwitch";
import { useInvertColors } from "@/contexts/InvertColorsContext";
import { n } from "@/utils/scaling";

export default function SettingsScreen() {
  const { invertColors, setInvertColors } = useInvertColors();
  const version = Constants.expoConfig?.version;
  const headerTitle = version ? `Settings (v${version})` : "Settings";

  return (
    <ContentContainer headerTitle={headerTitle} hideBackButton>
      <ToggleSwitch
        label="Invert Colours"
        onValueChange={setInvertColors}
        value={invertColors}
      />
      <StyledText style={{ fontSize: n(13), marginTop: n(24), opacity: 0.7 }}>
        Cloud sync (Dropbox / OneDrive) and Fog of World import arrive in the
        next milestones.
      </StyledText>
      <StyledText style={{ fontSize: n(12), marginTop: n(16), opacity: 0.5 }}>
        Map data © OpenStreetMap contributors
      </StyledText>
    </ContentContainer>
  );
}
