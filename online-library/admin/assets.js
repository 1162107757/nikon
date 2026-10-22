const ASSET_ID = /^(ncp|preview)-[a-f0-9]{64}$/;

function contentUrl(id, path, moduleUrl) {
  if (typeof id !== 'string' || !ASSET_ID.test(id)) return '';
  try {
    const url = new URL(`${path}${id}/content`, moduleUrl);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : '';
  } catch { return ''; }
}

// Private previews must use the current site's host-only session, including assets saved before a domain change.
export function adminPreviewUrl(asset, moduleUrl = import.meta.url) {
  return contentUrl(asset?.id, '../api/assets/', moduleUrl);
}

export function communityAssetUrl(id, moduleUrl = import.meta.url) {
  return contentUrl(id, '../api/community/assets/', moduleUrl);
}
