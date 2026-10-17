# JNI_OnLoad resolves this literal class name and registers its native methods.
-keep class dev.avifenesh.hermes.wake.MicroWakeWord {
    native <methods>;
}

# Retain the distribution-boundary class identities audited in the final DEX.
# Members may still be optimized; do not blanket-keep app or library packages.
-keep class dev.avifenesh.hermes.PhoneService
-keep class dev.avifenesh.hermes.PhoneStopReceiver
-keep class dev.avifenesh.hermes.PhoneNotice
-keep class dev.avifenesh.hermes.PhoneControlKt
-keep class dev.avifenesh.hermes.UnsupportedPhoneControl

# Do not retain debug code. If a reachable fixture ever crosses source sets,
# preserve its identity so the release absence guard cannot be fooled by renaming.
-keepnames class dev.avifenesh.hermes.**Probe**
-keepnames class dev.avifenesh.hermes.**Test**
-keepnames class dev.avifenesh.hermes.UiAuditActivity**
-keepnames class dev.avifenesh.hermes.BatteryPollingActivity**
