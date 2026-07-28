import { Tabs } from "expo-router";
import { Navbar, type TabConfigItem } from "@/components/Navbar";

export const TABS_CONFIG: readonly TabConfigItem[] = [
  { name: "Map", screenName: "index", iconName: "map" },
  { name: "Record", screenName: "record", iconName: "radio-button-checked" },
  { name: "Tracks", screenName: "tracks", iconName: "timeline" },
  { name: "Settings", screenName: "settings", iconName: "settings" },
] as const;

export default function TabLayout() {
  return (
    <Tabs
      backBehavior="none"
      sceneContainerStyle={{ backgroundColor: "black" }}
      screenOptions={{ animation: "none" }}
      tabBar={(props) => {
        const activeScreenName = props.state.routes[props.state.index].name;
        return (
          <Navbar
            currentScreenName={activeScreenName}
            navigation={props.navigation}
            tabsConfig={TABS_CONFIG}
          />
        );
      }}
    >
      {TABS_CONFIG.map((tab) => (
        <Tabs.Screen
          key={tab.screenName}
          name={tab.screenName}
          options={{ header: () => null }}
        />
      ))}
    </Tabs>
  );
}
