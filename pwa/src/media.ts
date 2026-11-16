/** Streaming manifests (HLS/DASH) are the real video; a plain file found earlier is often a placeholder. */
const MANIFEST_KINDS = new Set(['hls', 'hls-master', 'dash']);

/** True when a newly detected media item should replace the one the banner currently offers. */
export function shouldReplaceMedia(currentKind: string | null, newKind: string): boolean {
  if (currentKind === null) return true;
  return !MANIFEST_KINDS.has(currentKind) && MANIFEST_KINDS.has(newKind);
}
