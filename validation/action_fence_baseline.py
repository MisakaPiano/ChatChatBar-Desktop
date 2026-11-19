from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET


old = Path("old").resolve()
out = Path("action-fence-baseline").resolve()
out.mkdir()
source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=old, text=True).strip()
assert source == "0f57b676859d4f964a455dfeab3d31f7aaf3f600"
(out / "source.txt").write_text(source + "\n")
app = old / "app"
test_source = Path("fixture/validation/ActionOptionsFormatTest.kt")
shutil.copyfile(test_source, app / (
    "app/src/test/java/com/example/chatbar/domain/chat/ActionOptionsFormatTest.kt"
))
wrapper = app / "gradlew"
wrapper.chmod(wrapper.stat().st_mode | 0o111)
result = subprocess.run(
    ["./gradlew", ":app:testDebugUnitTest", "--tests",
     "com.example.chatbar.domain.chat.ActionOptionsFormatTest", "--console=plain", "--no-daemon"],
    cwd=app,
)
assert result.returncode != 0, "Expected a regression in the old implementation"
xml = app / (
    "app/build/test-results/testDebugUnitTest/"
    "TEST-com.example.chatbar.domain.chat.ActionOptionsFormatTest.xml"
)
assert xml.exists(), "Baseline must compile and actually execute the assertions"
shutil.copyfile(xml, out / xml.name)
cases = {test.attrib["name"]: test for test in ET.parse(xml).iter("testcase")}
assert len(cases) == 9 and all(c.find("error") is None and c.find("skipped") is None for c in cases.values())
failures = {name: case.find("failure") for name, case in cases.items() if case.find("failure") is not None}
assert set(failures) == {
    "legacyActionFencesRecognizeTheirExplicitOpeningOrBodyHeading",
    "declaredMarkdownFencesAgreeWithValidationAndTheRepairPreview",
    "impossibleOrAmbiguousFenceConfigurationsAreRejectedAtImportAndPreview",
    "imageAndVoiceUseTheCustomRuleButKeepActualStoryAndStatus",
}, failures
assert all("AssertionError" in f.attrib.get("type", "") for f in failures.values())
print("Old implementation reproduced four expected assertion failures; five control methods passed.")
