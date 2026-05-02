export const MAX_CACHE_SIZE = 500;

interface CacheEntry {
    height: number;
    renderJsonHash: number;
    layoutContextKey: string;
}

const cache = new Map<string, CacheEntry>();

export function computeRenderJsonHash(input: string): number {
    let hash = 5381;
    for (let i = 0; i < input.length; i++) {
        hash = ((hash << 5) + hash + input.charCodeAt(i)) | 0;
    }
    return hash >>> 0;
}

export function computeLayoutContextKey(
    themeJson: string | undefined,
    containerWidth: number
): string {
    return `${themeJson ?? ''}\x00${containerWidth}`;
}

export function getHeightCache(
    contentId: string,
    layoutContextKey: string,
    renderJsonHash: number
): number | null {
    const entry = cache.get(contentId);
    if (!entry) return null;
    if (
        entry.layoutContextKey !== layoutContextKey ||
        entry.renderJsonHash !== renderJsonHash
    ) {
        return null;
    }
    cache.delete(contentId);
    cache.set(contentId, entry);
    return entry.height;
}

export function setHeightCache(
    contentId: string,
    layoutContextKey: string,
    renderJsonHash: number,
    height: number
): void {
    cache.delete(contentId);
    if (cache.size >= MAX_CACHE_SIZE) {
        const oldestKey = cache.keys().next().value;
        if (oldestKey !== undefined) {
            cache.delete(oldestKey);
        }
    }
    cache.set(contentId, { height, renderJsonHash, layoutContextKey });
}

export function clearHeightCache(): void {
    cache.clear();
}
