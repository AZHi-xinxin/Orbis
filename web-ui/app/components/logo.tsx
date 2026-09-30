import type { ComponentPropsWithRef } from "react";

/** Code-native Orbis star; no private artwork is embedded in the public bundle. */
export default function Logo(props: ComponentPropsWithRef<"svg">) {
  return <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40" fill="none" aria-hidden="true" {...props}>
    <circle cx="20" cy="20" r="18" fill="currentColor" opacity=".08" />
    <path d="M20 2 24.65 13.61 37.12 14.44 27.53 22.45 30.58 34.56 20 27.92 9.42 34.56 12.47 22.45 2.88 14.44 15.35 13.61Z" fill="currentColor" />
  </svg>;
}

export function OrbisConstellation(props: ComponentPropsWithRef<"svg">) {
  return <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 72 44" fill="none" aria-hidden="true" {...props}>
    <path d="M8 13 25 11 31 27 14 31 8 13M25 11 41 18 54 13 66 21" stroke="currentColor" strokeWidth="1.2" opacity=".55" />
    {[[8, 13, 2.7], [25, 11, 3.1], [31, 27, 2.5], [14, 31, 2.8], [41, 18, 2.6], [54, 13, 3], [66, 21, 2.7]].map(([cx, cy, r], i) =>
      <circle key={i} cx={cx} cy={cy} r={r} fill="currentColor" />)}
  </svg>;
}
