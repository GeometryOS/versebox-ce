# Wall of Shame 02 — CI embedded an A9 ROM with an unresolved preload

## Incident

On 2026-11-27 at 12:25:55 (UTC+8), VM 3 (`1795737262140`) on a OnePlus GM1910 failed during the guest ART check. Launch record 49 reported that its ROM integrity check completed, followed by the exception below.

```text
java.lang.IllegalArgumentException: Missing runtime input: liba9vm-healthd-sysfs.so
    at io.a10vm.guestruntime.RuntimeFiles.nativeFile(RuntimeFiles.java:110)
    at io.a10vm.guestruntime.GuestRuntime.checkJava(GuestRuntime.java:424)
```

The installed `android9@1.0.1` had payload SHA-256 `47eef01992c4bafd6bb8582651f4b505a5e62daa97bd610430186c7b317e0642` and reported packer revision `3c2008c`. Its manifest named the bridge in `extraPreloads`, with no corresponding `files[].logical` entry. The locally staged ROM used the same version label and a different payload hash, `ffb921f84fa2525da356ebf61494dcce305be6861ebf348211f4fb8bba46ab26`. Its manifest and ZIP both contained the bridge under `payload/lib/`. The resident tar serves a separate set of inputs. The installed manifest also enabled both `installer` and `dexoptShim` while omitting `liba9vm-dexopt-shim.so`. The native lookup in the installer stage would therefore fail after the ART check.

## Cause and correction

The A9 release revision shipped the bridge source and standalone build script, but its one-command build never invoked that script. The prepare stage skipped absent bridge outputs and still emitted the preload reference. The ROM verifier and app staging verified the paths that were listed, leaving the unresolved reference undetected. An existing local bridge output could satisfy preparation while a clean runner omitted it.

The A9 build now schedules each bridge independently when its output is absent, empty, or older than its source or build script. Preparation fails if a required preload output is missing. The A9 packer checks both references before packing and during ZIP verification. The app's strict ROM staging checks them before accepting a release asset. Both bridges belong in the ROM payload.

## Verification and rollout

The fixture exercises both a broken reference and a valid manifest. Local verification compiled the bridge with NDK 29 for AArch64 API 28. Its 11,488-byte output matched the local ROM manifest SHA-256 `6b42928132ea82fe43fccbdd7a631000e74ce860f66a1d57d6089444ee1b6ce7`. All 13 A9 packaging and release-policy tests passed. The revised validator rejected both missing logical inputs in the manifest read from the device. A9 ZIP verification and app verification of the three locally staged ROMs passed.

Publish a rebuilt A9 release through the A9 repository, then run app CI against that validated release. Test VM 3 with the resulting APK and select the corrected ROM identity if the instance remains bound to the old payload. The installed APK’s A9 linker and seed-properties library hashes match the faulty ROM manifest and differ from the local ROM manifest. Pair the rebuilt ROM and its resident libraries in the same app build. An in-place APK update requires the device’s existing signing identity. A new CI artifact and the device startup result remain pending.
