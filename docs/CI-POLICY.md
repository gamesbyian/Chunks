# Lean CI policy
Copied as a principle, not as an archive of workflows, from wallgen, Glimpse and UR-Recomp.

One routine Android validation workflow when code/toolchain changes. Use shared account runner capacity responsibly; no redundant push+PR fanout for identical candidate heads, no recurring emulator screenshots, no broad version matrix on ordinary edits. Do not request write scopes or inject secrets into PR builds.

Routine: pinned JDK/Gradle/Android SDK → assemble debug APK → JVM unit tests → upload ephemeral debug artifact. Android 37 package compatibility must be observed in logs. Dependency changes are reviewed deliberately. Temporary CI research should be manually invoked or removed after the decision.

Targeted/manual escalation: representative emulator flows for UI, protected synthetic rTorrent fixture integration, real seedbox matrix, destructive disposable test volumes, accessibility captures. A full test campaign is justified only by high-risk filesystem or release changes.

Evidence hierarchy: source inspection < compile < deterministic fixture test < emulator < real external service/volume < physical Android behavior < human acceptance. Never claim a stronger class from a weaker one. Inspect the first failed CI step and current SHA, then remedy the actual failure. Branch protection should never require a job that can be skipped by an unrelated path filter.