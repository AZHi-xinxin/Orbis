export type BrandTheme = "orbis" | "deepseek";
/** Custom CSS is an advanced override, not a third branded theme. */
export type ColorTheme = BrandTheme | "custom";
export const BRAND_THEMES: readonly BrandTheme[] = ["orbis", "deepseek"];

export function welcomeName(nickname: string | null | undefined): string {
  return nickname?.trim() || "---";
}

type ThemeStorage = Pick<Storage, "getItem" | "setItem">;

/** Migrate the former palette list once, without deleting either custom CSS buffer. */
export function readColorTheme(
  storage: ThemeStorage,
  storageKey: string,
  fallback: ColorTheme = "orbis",
): ColorTheme {
  const colorKey = `${storageKey}-color`;
  const migrationKey = `${storageKey}-orbis-theme-version`;
  try {
    const stored = storage.getItem(colorKey);
    if (storage.getItem(migrationKey) !== "2") {
      if (stored && stored !== "orbis" && stored !== "deepseek") {
        if (storage.getItem(`${storageKey}-legacy-color`) === null) {
          storage.setItem(`${storageKey}-legacy-color`, stored);
        }
        storage.setItem(colorKey, "orbis");
        storage.setItem(migrationKey, "2");
        return "orbis";
      }
      storage.setItem(migrationKey, "2");
    }
    return stored === "orbis" || stored === "deepseek" || stored === "custom" ? stored : fallback;
  } catch {
    // Storage can be denied in private/embedded browsing; appearance must still render.
    return fallback;
  }
}
