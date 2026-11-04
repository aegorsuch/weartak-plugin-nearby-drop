# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import tarfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import fetch_sdk


class FetchSdkTest(unittest.TestCase):
    def test_sdk_source_uses_public_mirror_at_immutable_revision(self):
        self.assertEqual(
            "https://github.com/aegorsuch/weartak-plugin-sdk.git",
            fetch_sdk.SDK_URL,
        )
        self.assertEqual(
            "86bdc8b04797c2cf6caa30e78e796abac1c2a330",
            fetch_sdk.REVISION,
        )

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "source"
        self.repository.mkdir()
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "SDK Fixture")
        self.git("config", "user.email", "sdk-fixture@example.invalid")
        (self.repository / "LICENSE").write_text(
            "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n",
            encoding="utf-8",
        )
        (self.repository / "README.md").write_text(
            "`GPL-3.0-or-later`\n", encoding="utf-8"
        )
        (self.repository / "src").mkdir()
        (self.repository / "src" / "Api.kt").write_text("package fixture\n", encoding="utf-8")
        self.git("add", ".")
        self.git("-c", "user.name=SDK Fixture", "-c", "user.email=sdk-fixture@example.invalid",
                 "commit", "-m", "SDK fixture")
        self.revision = self.git("rev-parse", "HEAD")
        self.archive = self.root / "sdk-source.tar"

    def git(self, *arguments):
        result = subprocess.run(
            ["git", "-C", str(self.repository), *arguments],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if result.returncode:
            self.fail("Git fixture setup failed.")
        return result.stdout.decode("utf-8").strip()

    def run_fetch(self):
        with patch.dict(os.environ, {"CI_JOB_TOKEN": "", "GITLAB_TOKEN": ""}), \
                patch.object(fetch_sdk, "SDK_URL", self.repository.as_uri()), \
                patch.object(fetch_sdk, "REVISION", self.revision), \
                patch.object(fetch_sdk, "ARCHIVE", self.archive), \
                contextlib.redirect_stdout(io.StringIO()):
            fetch_sdk.fetch()

    def test_archives_exact_revision_with_its_license(self):
        self.run_fetch()
        self.assertTrue(self.archive.is_file())
        with tarfile.open(self.archive, mode="r:") as source:
            listed = source.getnames()
        self.assertIn("LICENSE", listed)
        self.assertIn("src/Api.kt", listed)

    def test_archive_preserves_repository_line_endings(self):
        crlf = {"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "core.autocrlf",
                "GIT_CONFIG_VALUE_0": "true"}
        with patch.dict(os.environ, crlf):
            self.run_fetch()
        with tarfile.open(self.archive, mode="r:") as source:
            content = source.extractfile("src/Api.kt").read()
        self.assertEqual(b"package fixture\n", content)

    def test_unlicensed_source_never_replaces_existing_archive(self):
        (self.repository / "LICENSE").write_text("All rights reserved.\n", encoding="utf-8")
        self.git("add", "LICENSE")
        self.git("-c", "user.name=SDK Fixture", "-c", "user.email=sdk-fixture@example.invalid",
                 "commit", "-m", "Remove license")
        self.revision = self.git("rev-parse", "HEAD")
        self.archive.write_bytes(b"existing")
        with self.assertRaisesRegex(RuntimeError, "lacks the verified"):
            self.run_fetch()
        self.assertEqual(b"existing", self.archive.read_bytes())

    def test_credentials_are_scoped_to_canonical_git_host(self):
        with patch.dict(os.environ, {"CI_JOB_TOKEN": "fixture-token"}, clear=True):
            environment = fetch_sdk._git_environment()
        self.assertEqual(
            "http.https://git.tak.gov/.extraheader",
            environment["GIT_CONFIG_KEY_0"],
        )
        self.assertEqual("JOB-TOKEN: fixture-token", environment["GIT_CONFIG_VALUE_0"])

    def test_checkout_error_does_not_expose_credentials_or_command_output(self):
        with patch.dict(os.environ, {"GITLAB_TOKEN": "do-not-echo"}, clear=True), \
                patch.object(fetch_sdk, "SDK_URL", (self.root / "missing").as_uri()), \
                patch.object(fetch_sdk, "ARCHIVE", self.archive), \
                patch.object(fetch_sdk.subprocess, "run",
                             return_value=SimpleNamespace(returncode=1, stdout=b"", stderr=b"do-not-echo")):
            with self.assertRaises(RuntimeError) as error:
                fetch_sdk.fetch()
        self.assertNotIn("do-not-echo", str(error.exception))


if __name__ == "__main__":
    unittest.main()
