const fs = require('fs');
const path = require('path');

const BUILTIN_PROFILES = [];

function resolveLive2dProfile(modelPath) {
    let profile = {
        id: 'default',
        motion: {},
        params: {},
        sources: ['default']
    };

    BUILTIN_PROFILES.forEach((entry) => {
        if (!matchesProfile(entry, modelPath)) {
            return;
        }
        profile = mergeProfile(profile, normalizeProfile(entry, entry.id || 'builtin'));
        profile.sources.push(`builtin:${entry.id || 'unnamed'}`);
    });

    const sidecarProfile = loadSidecarProfile(modelPath);
    if (sidecarProfile) {
        profile = mergeProfile(profile, sidecarProfile);
        profile.sources.push(`sidecar:${sidecarProfile.filePath}`);
    }

    return profile;
}

function loadSidecarProfile(modelPath) {
    if (typeof modelPath !== 'string' || !modelPath) {
        return null;
    }

    const candidates = getProfileCandidates(modelPath);
    for (const candidatePath of candidates) {
        if (!fs.existsSync(candidatePath)) {
            continue;
        }

        try {
            const raw = JSON.parse(fs.readFileSync(candidatePath, 'utf8'));
            const normalized = normalizeProfile(raw, path.parse(candidatePath).name);
            normalized.filePath = candidatePath;
            return normalized;
        } catch (error) {
            console.error(`[Live2DProfile] failed to parse profile: ${candidatePath}`, error);
        }
    }

    return null;
}

function getProfileCandidates(modelPath) {
    const dirPath = path.dirname(modelPath);
    const fileName = path.basename(modelPath);
    const stem = fileName.endsWith('.model3.json')
        ? fileName.slice(0, -'.model3.json'.length)
        : path.parse(fileName).name;

    return Array.from(new Set([
        path.join(dirPath, 'live2d.profile.json'),
        path.join(dirPath, `${stem}.profile.json`),
        path.join(dirPath, `${stem}.live2d.profile.json`)
    ]));
}

function matchesProfile(entry, modelPath) {
    if (!entry || typeof entry !== 'object' || typeof modelPath !== 'string' || !modelPath) {
        return false;
    }

    const normalizedPath = modelPath.replace(/\\/g, '/').toLowerCase();
    const baseName = path.basename(modelPath).toLowerCase();

    if (typeof entry.basename === 'string' && entry.basename.toLowerCase() === baseName) {
        return true;
    }

    if (typeof entry.includes === 'string' && normalizedPath.includes(entry.includes.toLowerCase())) {
        return true;
    }

    if (Array.isArray(entry.includes) && entry.includes.some((token) => (
        typeof token === 'string' && normalizedPath.includes(token.toLowerCase())
    ))) {
        return true;
    }

    return false;
}

function normalizeProfile(raw, fallbackId = 'profile') {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
        return {
            id: fallbackId,
            motion: {},
            params: {}
        };
    }

    return {
        id: typeof raw.id === 'string' && raw.id ? raw.id : fallbackId,
        motion: isPlainObject(raw.motion) ? raw.motion : {},
        params: isPlainObject(raw.params) ? raw.params : {}
    };
}

function mergeProfile(baseProfile, overrideProfile) {
    return {
        ...baseProfile,
        id: overrideProfile.id || baseProfile.id,
        motion: {
            ...baseProfile.motion,
            ...overrideProfile.motion
        },
        params: {
            ...baseProfile.params,
            ...overrideProfile.params
        },
        sources: [...(baseProfile.sources || [])]
    };
}

function isPlainObject(value) {
    return !!value && typeof value === 'object' && !Array.isArray(value);
}

module.exports = {
    resolveLive2dProfile
};
