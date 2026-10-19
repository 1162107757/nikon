# Online preset preview, download and deletion validation

Candidate: `dist/nikon-usb-0.11.1-beta-debug.apk`, version code 58 / 0.11.1-beta.

Product SHA256: `f1952cca9f98fdfaef4e01875eada653291e5f0ca535cd5cfd05630280a3a568`.

Independent native helper SHA256: `1545d20a966ccda3d0a0171342b1a751c1384f316f8389c88def0b9dda707a25`.

| Configuration | Native checks | Result | Evidence |
| --- | ---: | --- | --- |
| Light portrait | 75 | PASS | [Report](light-portrait/report.json) |
| Dark portrait | 75 | PASS | [Report](dark-portrait/report.json) |
| Light landscape | 76 | PASS | [Report](light-landscape/report.json) |

Each configuration includes eight screenshots. All 24 screenshots were visually reviewed. Portrait preview cards, detail effect/original comparison, unsupported state, staged download controls and deletion confirmation fit the existing interface. The landscape home page retains its existing tall fixed header: the first viewport has little remaining space for the grid and its bottom navigation overlaps that space. Detail content remains scrollable. No product changes were made during this validation.

The helper uses three pinned genuine recipes: one NCP, one supported Flexible Color NP3 and one explicitly unsupported NP3. Effect pixel hashes differ from the template hash, and pressing/releasing the original control restores the exact expected bitmaps. This demonstrates the app's approximate rendering for those fixtures, not Nikon-native colour equivalence or support for all NP3 files.

Downloads use an injected HTTPS transport with controlled timing and no public-network writes. A held optional-sample response confirms that source commit is not presented as overall completion: controls remain active with an unknown-length loading stage, even after leaving and reopening the detail page. Duplicate clicks do not start another recipe or sample fetch. A briefly visible top notice in completion screenshots comes from the deliberate duplicate-click test; button and task state are already terminal.

Deletion is blocked while that task is active, then exercised through the actual confirmation dialog. Reopening the file-backed preset library confirms that the deleted record does not return. This is a persistence/reopen check, not a forced Android process-kill test. Host tests separately cover tombstones, legacy migration, protected/shared/external samples, project-default reference checks and partial-failure behavior. An independent source reviewer verified the two Activity lifecycle findings were fixed before this candidate was built.

All three native runs report `ownedFixtureCleaned: true`. They used only the explicitly authorized `emulator-5560`, private owned fixture directories, guest state and injected transports. The helper restored appearance and rotation and was uninstalled after testing. The device was released to the next agent. No physical phone, account, camera file or cloud record was written.
