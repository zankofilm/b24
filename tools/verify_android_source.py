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
