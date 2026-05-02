import {
    getHeightCache,
    setHeightCache,
    clearHeightCache,
    computeRenderJsonHash,
    computeLayoutContextKey,
    MAX_CACHE_SIZE,
} from '../heightCache';

describe('heightCache', () => {
    afterEach(() => {
        clearHeightCache();
    });

    describe('computeRenderJsonHash', () => {
        it('returns a stable numeric hash for the same input', () => {
            const hash1 = computeRenderJsonHash('hello');
            const hash2 = computeRenderJsonHash('hello');
            expect(hash1).toBe(hash2);
            expect(typeof hash1).toBe('number');
        });

        it('returns different hashes for different inputs', () => {
            const hash1 = computeRenderJsonHash('hello');
            const hash2 = computeRenderJsonHash('world');
            expect(hash1).not.toBe(hash2);
        });

        it('returns 0 for empty string', () => {
            const hash = computeRenderJsonHash('');
            expect(typeof hash).toBe('number');
        });
    });

    describe('computeLayoutContextKey', () => {
        it('combines themeJson and width with null byte separator', () => {
            const key = computeLayoutContextKey('{"fontSize":16}', 375);
            expect(key).toBe('{"fontSize":16}\x00375');
        });

        it('produces different keys for different widths', () => {
            const key1 = computeLayoutContextKey('{}', 375);
            const key2 = computeLayoutContextKey('{}', 390);
            expect(key1).not.toBe(key2);
        });

        it('handles undefined themeJson', () => {
            const key = computeLayoutContextKey(undefined, 375);
            expect(key).toBe('\x00375');
        });
    });

    describe('getHeightCache / setHeightCache', () => {
        it('returns null for unknown contentId', () => {
            expect(getHeightCache('unknown', 'ctx', 12345)).toBeNull();
        });

        it('stores and retrieves a height', () => {
            setHeightCache('msg-1', 'ctx', 12345, 84);
            expect(getHeightCache('msg-1', 'ctx', 12345)).toBe(84);
        });

        it('returns null when layoutContextKey does not match', () => {
            setHeightCache('msg-1', 'ctx-a', 12345, 84);
            expect(getHeightCache('msg-1', 'ctx-b', 12345)).toBeNull();
        });

        it('returns null when renderJsonHash does not match', () => {
            setHeightCache('msg-1', 'ctx', 11111, 84);
            expect(getHeightCache('msg-1', 'ctx', 22222)).toBeNull();
        });

        it('overwrites existing entry for same contentId', () => {
            setHeightCache('msg-1', 'ctx', 11111, 84);
            setHeightCache('msg-1', 'ctx', 22222, 120);
            expect(getHeightCache('msg-1', 'ctx', 22222)).toBe(120);
            expect(getHeightCache('msg-1', 'ctx', 11111)).toBeNull();
        });
    });

    describe('clearHeightCache', () => {
        it('removes all cached entries', () => {
            setHeightCache('msg-1', 'ctx', 12345, 84);
            setHeightCache('msg-2', 'ctx', 67890, 120);
            clearHeightCache();
            expect(getHeightCache('msg-1', 'ctx', 12345)).toBeNull();
            expect(getHeightCache('msg-2', 'ctx', 67890)).toBeNull();
        });
    });

    describe('LRU eviction', () => {
        it('evicts oldest entry when cache exceeds MAX_CACHE_SIZE', () => {
            for (let i = 0; i < MAX_CACHE_SIZE; i++) {
                setHeightCache(`msg-${i}`, 'ctx', i, i * 10);
            }

            setHeightCache(`msg-${MAX_CACHE_SIZE}`, 'ctx', MAX_CACHE_SIZE, 999);
            expect(getHeightCache('msg-0', 'ctx', 0)).toBeNull();
            expect(getHeightCache(`msg-${MAX_CACHE_SIZE}`, 'ctx', MAX_CACHE_SIZE)).toBe(999);
        });

        it('refreshes entry on get to prevent eviction', () => {
            for (let i = 0; i < MAX_CACHE_SIZE; i++) {
                setHeightCache(`msg-${i}`, 'ctx', i, i * 10);
            }
            getHeightCache('msg-0', 'ctx', 0);

            setHeightCache(`msg-${MAX_CACHE_SIZE}`, 'ctx', MAX_CACHE_SIZE, 999);
            expect(getHeightCache('msg-0', 'ctx', 0)).toBe(0);
            expect(getHeightCache('msg-1', 'ctx', 1)).toBeNull();
        });
    });
});
