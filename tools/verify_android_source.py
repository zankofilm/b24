#!/usr/bin/env python3
"""Fast pre-build checks for the BAZR Android Native project."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app" / "src" / "main"
errors: list[str] = []

# Validate all Android XML resources and the manifest.
xml_files = sorted(MAIN.rglob("*.xml"))
for path in xml_files:
    try:
        ET.parse(path)
    except Exception as exc:  # pragma: no cover - diagnostics only
        errors.append(f"XML parse error: {path.relative_to(ROOT)}: {exc}")

# Validate every R.drawable reference against drawable resources, including density folders.
source_text = "\n".join(
    path.read_text(encoding="utf-8", errors="replace")
    for path in (ROOT / "app" / "src" / "main" / "java").rglob("*.kt")
)
referenced = set(re.findall(r"R\.drawable\.([A-Za-z0-9_]+)", source_text))
resource_names = {
    path.stem
    for path in (MAIN / "res").rglob("*")
    if path.is_file() and path.parent.name.startswith("drawable")
}
for missing in sorted(referenced - resource_names):
    errors.append(f"Missing drawable resource: {missing}")

# Keep the release version and workflow artifact name synchronized.
gradle_file = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
version_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_file)
workflow = ROOT / ".github" / "workflows" / "android.yml"
if not workflow.is_file():
    errors.append("Missing GitHub Actions workflow: .github/workflows/android.yml")
elif version_match:
    workflow_text = workflow.read_text(encoding="utf-8")
    version = version_match.group(1)
    if f"bazr-inspector-{version}-apk" not in workflow_text:
        errors.append(f"Workflow artifact name does not match versionName {version}")
else:
    errors.append("Could not read versionName from app/build.gradle.kts")

# Regression guard: Kotlin Map.entries is a Set, so convert it to a List before takeLast().
main_activity = (MAIN / "java" / "ir" / "bazr" / "inspection" / "MainActivity.kt").read_text(encoding="utf-8")
if re.search(r"\.entries\s*\.takeLast\s*\(", main_activity):
    errors.append("Invalid trend chart collection operation: convert Map.entries to List before takeLast()")
if "entries.toList().takeLast(limit)" not in main_activity:
    errors.append("Trend chart should select its tail from a List using entries.toList().takeLast(limit)")

# field() adds its TextInputEditText inside a TextInputLayout to `content`.
# Re-adding the returned EditText directly causes IllegalStateException at runtime.
main_lines = main_activity.splitlines()
field_names = {
    match.group(1)
    for line in main_lines
    if (match := re.search(r"val\s+(\w+)\s*=\s*field\(", line))
}
for field_name in sorted(field_names):
    if re.search(r"content\.addView\(\s*" + re.escape(field_name) + r"\s*\)", main_activity):
        errors.append(
            f"Duplicate parent attachment for field '{field_name}': field() already attaches its wrapper"
        )

diagnostics = MAIN / "java" / "ir" / "bazr" / "inspection" / "AppDiagnostics.kt"
if not diagnostics.is_file():
    errors.append("Missing on-device diagnostics helper: AppDiagnostics.kt")
if "private fun showDraftsRecoveryScreen" not in main_activity or "AppDiagnostics.record(this, \"open_drafts\"" not in main_activity:
    errors.append("Drafts screen must have a recoverable error boundary and diagnostic capture")


# Version 4.15 UI and draft workflow regression guards.
if 'versionName = "4.15.0"' not in gradle_file or 'versionCode = 29' not in gradle_file:
    errors.append("Expected BAZR Android 4.15.0 / versionCode 29")
for required in (
    'var renderDraftCards: () -> Unit = {}',
    '"در حال تکمیل"',
    '"آماده ارسال"',
    '"نیازمند پیگیری"',
    'پیشرفت بازرسی',
    'مشاهده صف ارسال',
    'ارسال گروهی گزارش‌های سالم',
    'private fun formatLocalTimestamp',
):
    if required not in main_activity:
        errors.append(f"Missing 4.15 draft workflow/UI element: {required}")
if 'Color.WHITE' not in main_activity[main_activity.find('private fun bottomNavigation()'):main_activity.find('private fun statCard(')]:
    errors.append("Version 4.15 bottom navigation should use the approved light surface")
inspection_draft = (MAIN / "java" / "ir" / "bazr" / "inspection" / "InspectionDraft.kt").read_text(encoding="utf-8")
if 'updatedAt = j.optString("updated_at", startedAt)' not in inspection_draft:
    errors.append("Draft timestamp migration must remain backward-compatible")
if '.put("updated_at", updatedAt)' not in inspection_draft:
    errors.append("Draft save should persist the last local update timestamp")

# Required visual assets used by the approved login/dashboard design.
for name in ("login_building.webp", "iran_flag_ribbon.webp"):
    if not (MAIN / "res" / "drawable-nodpi" / name).is_file():
        errors.append(f"Missing required design asset: {name}")

if errors:
    print("BAZR source verification FAILED")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("BAZR source verification PASSED")
print(f" - XML files parsed: {len(xml_files)}")
print(f" - Drawable references verified: {len(referenced)}")
print(" - GitHub artifact version synchronized")
print(" - Required login design assets present")
print(" - 4.15 draft filters, progress and queue guards verified")
print(" - Backward-compatible local draft timestamp verified")
print(" - Duplicate TextInput parent attachment guard enabled")
