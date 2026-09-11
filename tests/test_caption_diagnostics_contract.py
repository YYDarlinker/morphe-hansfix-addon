"""Bounded diagnostics contracts; runtime privacy/off behavior lives in Java tests.

Source declaration checks are static preflight, not a loaded-bundle or device test.
"""
import copy
import importlib.util
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("diagnostics_safety", ROOT / "tools/check_publish_safety.py")
safety = importlib.util.module_from_spec(spec)
spec.loader.exec_module(safety)
DIAGNOSTICS = "Caption request diagnostics"
MEMORY = "Remember subtitle language"


def patch(name):
    return {
        "name": name,
        "description": "Caption addon feature.",
        "default": name == MEMORY,
        "options": [],
        "compatiblePackages": [{
            "packageName": "com.google.android.youtube",
            "targets": [{"version": "21.13.164", "isExperimental": False}],
        }],
    }


def metadata(names):
    return {"version": "1.2.3", "patches": [patch(name) for name in names]}


def issues(value):
    return safety.patch_list_issues(json.dumps(value).encode())


class DiagnosticsMetadataTests(unittest.TestCase):
    def test_old_one_and_two_patch_metadata_and_new_three_patch_metadata(self):
        for names in ((safety.PATCH_NAME,), (safety.PATCH_NAME, MEMORY),
                      (safety.PATCH_NAME, MEMORY, DIAGNOSTICS)):
            with self.subTest(names=names):
                self.assertEqual([], issues(metadata(names)))

    def test_exact_count_names_and_uniqueness(self):
        for names in ((), (safety.PATCH_NAME, MEMORY, DIAGNOSTICS, "Other"),
                      (safety.PATCH_NAME, MEMORY, "Other"),
                      (safety.PATCH_NAME, DIAGNOSTICS, DIAGNOSTICS)):
            with self.subTest(names=names):
                self.assertTrue(issues(metadata(names)))

    def test_diagnostics_default_requires_boolean_false(self):
        for default in (True, None, 0, "false", []):
            value = metadata((safety.PATCH_NAME, MEMORY, DIAGNOSTICS))
            value["patches"][-1]["default"] = default
            with self.subTest(default=default):
                self.assertIn("unexpected-generated-patch-default", issues(value))
        value["patches"][-1].pop("default")
        self.assertIn("unexpected-generated-patch-default", issues(value))

    def test_diagnostics_has_no_options(self):
        value = metadata((safety.PATCH_NAME, MEMORY, DIAGNOSTICS))
        self.assertEqual([], issues(value))
        value["patches"][-1].pop("options")
        self.assertEqual([], issues(value))
        for options in ([{"key": "enabled"}], {}, None, False, ""):
            value["patches"][-1]["options"] = options
            with self.subTest(options=options):
                self.assertIn("unexpected-generated-patch-options", issues(value))

    def test_existing_defaults_unchanged(self):
        for name, default in ((safety.PATCH_NAME, True), (MEMORY, False)):
            value = metadata((name,))
            value["patches"][0]["default"] = default
            self.assertIn("unexpected-generated-patch-default", issues(value))

    def test_diagnostics_keeps_description_package_and_target_gates(self):
        original = metadata((safety.PATCH_NAME, MEMORY, DIAGNOSTICS))
        mutations = (
            lambda p: p.update(description=""),
            lambda p: p.update(compatiblePackages=[]),
            lambda p: p["compatiblePackages"][0].update(packageName="other.package"),
            lambda p: p["compatiblePackages"][0].update(targets=[]),
            lambda p: p["compatiblePackages"][0]["targets"][0].update(version="0.0.0"),
        )
        for mutate in mutations:
            value = copy.deepcopy(original)
            mutate(value["patches"][-1])
            self.assertTrue(issues(value))

    def test_release_candidate_still_requires_both_existing_features(self):
        manifest = {
            "version": "1.2.3", "created_at": "2026-09-11T00:00:00",
            "description": "Caption addon release.",
            "download_url": f"https://github.com/{safety.REPOSITORY}/releases/download/v1.2.3/patches-1.2.3.mpp",
        }
        for names, accepted in (((safety.PATCH_NAME, MEMORY), True),
                                ((safety.PATCH_NAME, MEMORY, DIAGNOSTICS), True),
                                ((safety.PATCH_NAME,), False),
                                ((safety.PATCH_NAME, DIAGNOSTICS), False),
                                ((MEMORY, DIAGNOSTICS), False)):
            files = {"patches-bundle.json": json.dumps(manifest).encode(),
                     "patches-list.json": json.dumps(metadata(names)).encode(),
                     "gradle.properties": b"version = 1.2.3\n"}
            result = safety.metadata_contract(files, required=True, expected_version="1.2.3")
            with self.subTest(names=names):
                self.assertEqual(accepted, not result)
                if not accepted:
                    self.assertIn(("patches-list.json", "release-must-include-both-caption-patches"), result)


class DiagnosticsSourceTests(unittest.TestCase):
    def test_production_declaration_is_opt_in_and_has_no_patch_options(self):
        path = ROOT / "patches/src/main/kotlin/io/github/yydarlinker/hansfix/CaptionDiagnosticsPatch.kt"
        source = path.read_text(encoding="utf-8")
        # Ignore comments and arbitrary whitespace rather than matching exact source lines.
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
        declarations = re.findall(r"\bbytecodePatch\s*\((.*?)\)\s*\{", code, re.S)
        matching = [d for d in declarations if re.search(r'\bname\s*=\s*"' + re.escape(DIAGNOSTICS) + r'"', d)]
        self.assertEqual(1, len(matching), "Exactly one named diagnostics declaration required")
        self.assertRegex(matching[0], r"\bdefault\s*=\s*false\b")
        self.assertNotRegex(code, r"\b\w*Option\s*\(", "Diagnostics must not expose patch options")

    def test_only_diagnostics_may_extend_existing_registration_count(self):
        base = {
            "patches/src/main/kotlin/Features.kt": b'val a = bytecodePatch(name = "HansFix") {}\nval b = bytecodePatch(name = "Remember subtitle language") {}\nval shared = bytecodePatch(description = "Shared runtime") {}',
            "extensions/extension/src/main/java/Runtime.java": b"package io.github.yydarlinker.hansfix; class Runtime {}",
        }
        self.assertEqual([], safety.source_contract(base))
        path = "patches/src/main/kotlin/Diagnostics.kt"
        declaration = b'val diagnostics = bytecodePatch(name = "Caption request diagnostics", default = false) {}'
        self.assertEqual([], safety.source_contract(dict(base, **{path: declaration})))
        for added in (declaration.replace(b"Caption request diagnostics", b"Other"),
                      declaration + b"\n" + declaration,
                      declaration + b'\nval other = resourcePatch(name = "Other") {}'):
            self.assertTrue(safety.source_contract(dict(base, **{path: added})))


if __name__ == "__main__":
    unittest.main()
