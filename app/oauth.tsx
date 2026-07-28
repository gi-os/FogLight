import { router, useLocalSearchParams } from "expo-router";
import { useEffect, useState } from "react";
import ContentContainer from "@/components/ContentContainer";
import { StyledText } from "@/components/StyledText";
import { finishAuth } from "@/services/cloud/dropbox";
import { n } from "@/utils/scaling";

export default function OAuthScreen() {
  const { code, error } = useLocalSearchParams<{ code?: string; error?: string }>();
  const [status, setStatus] = useState("Connecting to Dropbox…");

  useEffect(() => {
    (async () => {
      if (error || !code) {
        setStatus(`Dropbox authorization failed${error ? `: ${error}` : "."}`);
        return;
      }
      try {
        await finishAuth(String(code));
        router.replace("/(tabs)/settings");
      } catch (e) {
        setStatus(e instanceof Error ? e.message : "Connection failed.");
      }
    })();
  }, [code, error]);

  return (
    <ContentContainer headerTitle="Dropbox">
      <StyledText style={{ fontSize: n(14) }}>{status}</StyledText>
    </ContentContainer>
  );
}
