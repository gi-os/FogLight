import { useRef, useState } from "react";
import { type LayoutChangeEvent, PanResponder, View } from "react-native";
import { useInvertColors } from "@/contexts/InvertColorsContext";
import { n } from "@/utils/scaling";
import { StyledText } from "./StyledText";

interface SliderRowProps {
  label: string;
  max: number;
  min: number;
  onChange: (value: number) => void;
  step?: number;
  /** render the current value, e.g. (v) => `${v}%` */
  format?: (value: number) => string;
  value: number;
}

/** LightOS-style slider: hairline track, square thumb, no native deps. */
export function SliderRow({
  label,
  value,
  min,
  max,
  step = 5,
  format,
  onChange,
}: SliderRowProps) {
  const { invertColors } = useInvertColors();
  const [trackWidth, setTrackWidth] = useState(0);
  const trackWidthRef = useRef(0);
  const fg = invertColors ? "black" : "white";
  const dim = invertColors ? "#666666" : "#6E6E6E";

  const valueFromX = (x: number) => {
    const w = trackWidthRef.current;
    if (w <= 0) return value;
    const ratio = Math.min(1, Math.max(0, x / w));
    const raw = min + ratio * (max - min);
    return Math.min(max, Math.max(min, Math.round(raw / step) * step));
  };

  const pan = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (evt) => onChange(valueFromX(evt.nativeEvent.locationX)),
      onPanResponderMove: (evt) => onChange(valueFromX(evt.nativeEvent.locationX)),
    })
  ).current;

  const onLayout = (e: LayoutChangeEvent) => {
    trackWidthRef.current = e.nativeEvent.layout.width;
    setTrackWidth(e.nativeEvent.layout.width);
  };

  const ratio = (value - min) / (max - min);
  const thumbLeft = Math.max(0, ratio * trackWidth - n(8));

  return (
    <View style={{ marginTop: n(16) }}>
      <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
        <StyledText style={{ fontSize: n(14) }}>{label}</StyledText>
        <StyledText style={{ fontSize: n(14) }}>
          {format ? format(value) : String(value)}
        </StyledText>
      </View>
      <View
        {...pan.panHandlers}
        onLayout={onLayout}
        style={{ height: n(36), justifyContent: "center" }}
      >
        <View style={{ height: 1, backgroundColor: dim }} />
        <View
          style={{
            position: "absolute",
            left: thumbLeft,
            width: n(16),
            height: n(16),
            backgroundColor: fg,
          }}
        />
      </View>
    </View>
  );
}
